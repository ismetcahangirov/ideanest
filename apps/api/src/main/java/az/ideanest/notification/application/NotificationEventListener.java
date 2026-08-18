package az.ideanest.notification.application;

import az.ideanest.notification.application.NotificationEvents.GoalReached;
import az.ideanest.notification.application.NotificationEvents.PaymentFailed;
import az.ideanest.notification.application.NotificationEvents.PledgeConfirmed;
import az.ideanest.notification.application.NotificationEvents.PledgeEdited;
import az.ideanest.notification.application.NotificationEvents.ProjectApproved;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.shared.outbox.OutboxMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p><strong>The recipient always comes out of the payload.</strong> A translation may
 * not look up who ought to be told: a campaign's backers are rows in {@code pledges} and
 * its followers are rows in {@code project_reminders}, and reading either from here is
 * the coupling this whole arrangement exists to prevent. That is a real limit on what
 * #85 delivers and it is stated rather than worked around — {@link GoalReached} notifies
 * the creator and not the backers, and the pull request names the audience port that
 * would change it.
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
    private final ObjectMapper json;

    public NotificationEventListener(NotificationFanOut fanOut, ObjectMapper json) {
        this.fanOut = fanOut;
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
                yield List.of(NotificationRequest.about(
                        required(event.creatorId(), "creatorId", message),
                        NotificationType.GOAL_REACHED,
                        PROJECT,
                        required(event.projectId(), "projectId", message),
                        params("goal", event.goal()),
                        at(event.reachedAt(), message)));
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
