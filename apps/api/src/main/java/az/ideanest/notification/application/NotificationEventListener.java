package az.ideanest.notification.application;

import az.ideanest.notification.application.NotificationEvents.GoalReached;
import az.ideanest.notification.application.NotificationEvents.PaymentFailed;
import az.ideanest.notification.application.NotificationEvents.PledgeConfirmed;
import az.ideanest.notification.application.NotificationEvents.PledgeEdited;
import az.ideanest.notification.application.NotificationEvents.ProjectApproved;
import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.shared.audience.ProjectAudience;
import az.ideanest.shared.audience.ProjectAudiences;
import az.ideanest.shared.outbox.OutboxMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * How the notification module hears that something happened.
 *
 * <p>A listener on {@code OutboxMessage}, switching on {@link OutboxMessage#eventType()},
 * because that is the shape {@code ApplicationEventOutboxDispatcher} prescribes in its
 * own class comment: the dispatcher stays untyped so that no per-event Java class
 * becomes a compile-time coupling between the relay and every module's events, and so
 * that nothing has to be rebuilt the day these messages arrive from another service
 * instead of from a table.
 *
 * <p><strong>This is the whole of the coupling between notifications and the modules
 * that produce these events, and it is a string.</strong> {@code ModuleBoundaryTests}
 * forbids reaching into another module's domain or infrastructure; a listener on a
 * published event reaches into neither. Nothing in this file imports anything from
 * {@code az.ideanest.pledge} or {@code az.ideanest.project}, which is checkable, is
 * checked, and is asserted directly by {@code NotificationBoundaryTests}.
 *
 * <p><strong>Nothing publishes these events yet.</strong> {@link NotificationEvents}
 * says so at length, including what the remaining work is and why it is not in this
 * change. Until a producer lands this listener sees no traffic and {@code notifications}
 * stays empty.
 *
 * <h2>Translation, and what this module is allowed to know</h2>
 *
 * <p>Each branch below turns one payload into {@link NotificationRequest}s: who to tell,
 * which of §4.10's rows it is, and what a template will need. It does not decide which
 * channels — that is §4.10's table, on {@link NotificationType} — and it does not decide
 * whether to send, which is the recipient's preference and {@code NotificationFanOut}'s
 * job.
 *
 * <p><strong>A recipient comes out of the payload, or out of a published port. Never out of
 * another module's tables.</strong> A translation may not look up who ought to be told by
 * reading {@code pledges} or {@code project_reminders}; that is the coupling this whole
 * arrangement exists to prevent, and nothing in this file imports anything from
 * {@code az.ideanest.pledge} or {@code az.ideanest.project}.
 *
 * <p>#85 stopped there, which meant {@link GoalReached} notified the creator and nobody else —
 * the least useful half of that event, since the people who funded the campaign heard nothing.
 * #245 is the port that fixes it: {@code shared.audience.ProjectAudiences} is a question the
 * pledge module answers about its own rows, so the audience is <em>asked for</em> rather than
 * read. {@link #backersOf} is the one call site, and it is where the bound on a computed
 * audience is applied and logged.
 *
 * <p><strong>Followers are still not expressible</strong>, and that is a missing table rather
 * than a missing port: #90 owns saving and following, so {@code ProjectAudience} has no
 * {@code FOLLOWERS} constant to ask for — that enum says why adding one now would be worse than
 * leaving it out. §4.10's {@code FOLLOWED_CREATOR_LAUNCHED} and
 * {@code SAVED_PROJECT_ENDING_SOON} therefore have no audience yet.
 *
 * <h2>Failure</h2>
 *
 * <p>Three kinds, and they are answered differently on purpose.
 *
 * <ul>
 *   <li><strong>An event this module does not recognise.</strong> Ignored, silently, and
 *       that is the only correct answer: the dispatcher publishes every event to every
 *       listener, so a comment being posted reaches this method too, and treating an
 *       unrecognised type as a fault would make every module's events every other
 *       module's problem.
 *   <li><strong>The notifications failed to write.</strong> It throws, the dispatch
 *       transaction rolls back with it, the event stays {@code PENDING}, and the relay
 *       tries again. That is what an at-least-once transport is for and it is the reason
 *       {@code NotificationFanOut} is {@code MANDATORY}.
 *   <li><strong>The payload cannot be read, or is missing something this module needs.</strong>
 *       It throws too, and that is a deliberate choice rather than an oversight. An
 *       event whose body this module does not understand will not be understood on the
 *       next attempt either, so the retries are wasted and the eighth one dead-letters
 *       it — which is exactly right: a producer and a consumer that disagree about a
 *       payload is a fault somebody has to see, and swallowing it would mean people
 *       silently not being told things, with nothing anywhere saying so. A notification
 *       nobody receives and nobody misses is the worst failure this module has.
 * </ul>
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    /** §7.2's aggregate names, as they appear on a notification's subject. */
    private static final String PLEDGE = "pledge";

    private static final String PROJECT = "project";

    private final NotificationFanOut fanOut;
    private final ProjectAudiences audiences;
    private final NotificationProperties properties;
    private final ObjectMapper json;

    public NotificationEventListener(
            NotificationFanOut fanOut,
            ProjectAudiences audiences,
            NotificationProperties properties,
            ObjectMapper json) {

        this.fanOut = fanOut;
        this.audiences = audiences;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Fans out the event this message describes, and ignores every other event.
     *
     * <p>Synchronous, and it has to be: {@code ApplicationEventOutboxDispatcher}'s
     * comment states that an {@code @Async} listener would make every event look
     * delivered the instant it was handed over, which is the one behaviour the
     * dispatcher asks an implementation not to have. It also runs inside the dispatch
     * transaction, which is what {@code NotificationFanOut}'s {@code MANDATORY}
     * propagation depends on.
     */
    @EventListener
    public void on(OutboxMessage message) {
        List<NotificationRequest> requests = translate(message);
        if (requests == null) {
            return;
        }
        if (message.deliveryAttempt() > 1) {
            // Never in itself an error — OutboxMessage says so — and worth a line,
            // because a redelivery that keeps happening is the shape of a poisoned
            // event and this is where it would be visible.
            log.info("Fanning out {} on delivery attempt {}", message, message.deliveryAttempt());
        }
        fanOut.fanOut(message.id(), requests);
    }

    /**
     * The requests this event owes, or null when the event is not this module's.
     *
     * <p>Null rather than an empty list, because the two are different facts and only
     * one of them is worth logging: "not mine" is every other module's traffic, and
     * "mine, and it concerns nobody" is a translation that decided something.
     */
    private List<NotificationRequest> translate(OutboxMessage message) {
        return switch (message.eventType()) {
            case PledgeConfirmed.EVENT_TYPE -> {
                PledgeConfirmed event = read(message, PledgeConfirmed.class);
                yield List.of(NotificationRequest.about(
                        required(event.backerId(), "backerId", message),
                        NotificationType.PLEDGE_CONFIRMED,
                        PLEDGE,
                        required(event.pledgeId(), "pledgeId", message),
                        params("projectId", event.projectId(), "total", event.total()),
                        at(event.confirmedAt(), message)));
            }
            case PledgeEdited.EVENT_TYPE -> {
                PledgeEdited event = read(message, PledgeEdited.class);
                yield List.of(NotificationRequest.about(
                        required(event.backerId(), "backerId", message),
                        NotificationType.PLEDGE_EDITED,
                        PLEDGE,
                        required(event.pledgeId(), "pledgeId", message),
                        params("projectId", event.projectId(), "total", event.total()),
                        at(event.editedAt(), message)));
            }
            case PaymentFailed.EVENT_TYPE -> {
                PaymentFailed event = read(message, PaymentFailed.class);
                yield List.of(NotificationRequest.about(
                        required(event.backerId(), "backerId", message),
                        NotificationType.PAYMENT_FAILED,
                        PLEDGE,
                        required(event.pledgeId(), "pledgeId", message),
                        params(
                                "projectId", event.projectId(),
                                "amount", event.amount(),
                                "attempt", event.attempt()),
                        at(event.failedAt(), message)));
            }
            case GoalReached.EVENT_TYPE -> {
                GoalReached event = read(message, GoalReached.class);
                UUID projectId = required(event.projectId(), "projectId", message);
                UUID creatorId = required(event.creatorId(), "creatorId", message);
                yield everybody(
                        // The creator first, and never dropped by the bound below: it is their
                        // campaign, and a truncated audience that lost the one recipient who is
                        // certain to want the message would be the wrong thing to cut.
                        concat(creatorId, backersOf(projectId, message)),
                        NotificationType.GOAL_REACHED,
                        projectId,
                        params("goal", event.goal()),
                        at(event.reachedAt(), message));
            }
            case ProjectApproved.EVENT_TYPE -> {
                ProjectApproved event = read(message, ProjectApproved.class);
                yield List.of(NotificationRequest.about(
                        required(event.creatorId(), "creatorId", message),
                        NotificationType.PROJECT_APPROVED,
                        PROJECT,
                        required(event.projectId(), "projectId", message),
                        params(),
                        at(event.approvedAt(), message)));
            }
            default -> null;
        };
    }

    /**
     * The payload, as this module's contract for it.
     *
     * <p>Read with the application's own {@code ObjectMapper}, so that the money in it
     * is subject to §10.3's rules — an amount as a string, never a JSON number — without
     * this class knowing that is what it is asking for.
     */
    private <T> T read(OutboxMessage message, Class<T> shape) {
        try {
            T event = json.readValue(message.payload(), shape);
            if (event == null) {
                // A payload of the four characters `null` parses successfully and yields
                // nothing. Caught here so the failure names the event rather than
                // surfacing as a NullPointerException three lines later.
                throw new IllegalStateException(
                        "A " + message.eventType() + " event " + message.id() + " has an empty body");
            }
            return event;
        } catch (JacksonException malformed) {
            // Unchecked in Jackson 3, and still caught, for Outbox's reason: a raw
            // databind error surfacing from the middle of a dispatch says nothing about
            // which event it was about. It must fail — see the class comment for why
            // swallowing it would be worse — but it must fail saying so.
            throw new IllegalStateException(
                    "A " + message.eventType() + " event " + message.id() + " could not be read as one", malformed);
        }
    }

    /**
     * The same notification, to several people.
     *
     * <p>One {@link NotificationRequest} each, because a request is one person — the fan-out
     * resolves preferences per recipient, so a broadcast has to be several requests or it would
     * be one message that ignored everybody's settings but the first person's.
     */
    private static List<NotificationRequest> everybody(
            List<UUID> recipients,
            NotificationType type,
            UUID projectId,
            Map<String, Object> params,
            Instant occurredAt) {

        return recipients.stream()
                .map(recipientId ->
                        NotificationRequest.about(recipientId, type, PROJECT, projectId, params, occurredAt))
                .toList();
    }

    /**
     * One recipient in front of a list, without repeating anybody.
     *
     * <p><strong>The deduplication is load-bearing rather than tidy.</strong>
     * {@code notifications_event_recipient_channel_key} is unique on (event, recipient, channel),
     * so a creator who has also backed their own campaign would appear twice, the second insert
     * would violate the index, and the whole dispatch would roll back and be retried — for ever,
     * because no redelivery changes the audience. A {@link LinkedHashSet} so the order stays the
     * one written here.
     */
    private static List<UUID> concat(UUID first, List<UUID> rest) {
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(first);
        recipients.addAll(rest);
        return List.copyOf(recipients);
    }

    /**
     * The campaign's backers, from the port the pledge module publishes — #245.
     *
     * <p><strong>The bound is applied here and never silently.</strong> One more than the ceiling
     * is asked for, so "there were more" is a fact this method knows rather than one it infers
     * from a full page; when there were, it logs at {@code ERROR} naming the campaign and the
     * count, because the notifications that fall off the end are people the platform decided not
     * to tell. {@code NotificationProperties.Audience} argues the number and says what removes
     * the bound rather than raising it.
     *
     * <p>Not a failure, for {@code NotificationFanOut}'s reason: this listener shares the dispatch
     * transaction with every other consumer of the event, so throwing would destroy their writes
     * over a condition no redelivery can fix.
     */
    private List<UUID> backersOf(UUID projectId, OutboxMessage message) {
        int ceiling = properties.audience().maxRecipients();
        List<UUID> backers = audiences.membersOf(projectId, ProjectAudience.BACKERS, ceiling + 1);

        if (backers.size() <= ceiling) {
            return backers;
        }
        log.error(
                "Campaign {} has more than {} backers, so a {} notification from event {} reaches the first {}"
                        + " of them and the rest are not told; the fan-out has to be chunked to remove this bound",
                projectId,
                ceiling,
                message.eventType(),
                message.id(),
                ceiling);
        return backers.subList(0, ceiling);
    }

    /**
     * A field the translation cannot proceed without.
     *
     * <p>Separate from the parse so that the message names the field. "Cannot read the
     * payload" and "read it, and there is nobody to tell" are different faults for
     * whoever has to fix the producer, and the first one wearing the second's name costs
     * an hour.
     */
    private static <T> T required(T value, String field, OutboxMessage message) {
        if (value == null) {
            throw new IllegalStateException(
                    "A " + message.eventType() + " event " + message.id() + " carries no " + field);
        }
        return value;
    }

    /** The instant the event reports, which every type of event must carry. */
    private static Instant at(Instant occurredAt, OutboxMessage message) {
        return required(occurredAt, "occurrence instant", message);
    }

    /**
     * The rendering document, as pairs.
     *
     * <p>A {@link LinkedHashMap} so the JSON comes out in the order written here, which
     * makes a stored document readable by whoever is debugging a template. Null values
     * are dropped rather than written: an absent key and a null one mean the same thing
     * to a template, and only one of them survives a round trip through jsonb
     * predictably.
     */
    private static Map<String, Object> params(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Parameters are name and value pairs");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value != null) {
                params.put(Objects.toString(pairs[index]), value);
            }
        }
        return params;
    }
}
