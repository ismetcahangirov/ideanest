package az.ideanest.notification.application;

import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.DeliveryPolicy;
import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationPreference;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.notification.infrastructure.NotificationPreferenceRepository;
import az.ideanest.notification.infrastructure.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * §12.2's middle box: one event, the recipient's preferences, and the rows that result.
 *
 * <p>The fan-out is over channels, not over people. §4.10 gives each type its columns —
 * "pledge confirmed" has three, "pledge edited" has two — and each column is resolved
 * separately against what the recipient asked for. One request therefore becomes
 * between zero and three notifications, and zero is an ordinary outcome rather than a
 * failure: somebody who turned a category off is honoured by nothing being written.
 *
 * <h2>Written in the delivery's transaction. {@code MANDATORY}, deliberately</h2>
 *
 * <p>{@code OutboxDispatch} claims an event, publishes it, and records that it went, all
 * in one transaction. Joining that transaction is what makes "the event was delivered"
 * and "the notifications exist" one fact, and it is what closes both halves of
 * {@code Outbox}'s argument at once:
 *
 * <ul>
 *   <li><strong>Nothing is produced for a state change that rolled back.</strong> The
 *       event row itself was written by the business transaction, so an event exists
 *       only if the change did.
 *   <li><strong>Nothing is lost when the process dies.</strong> A failure here rolls the
 *       dispatch back with it, the event stays {@code PENDING}, and the relay tries
 *       again.
 * </ul>
 *
 * <p>{@code ReferralAttributionService} enumerates the alternatives and they are the
 * same ones: {@code REQUIRES_NEW} would leave notifications behind for a delivery that
 * then failed and will be replayed, and {@code REQUIRED} would quietly open a
 * transaction for a caller that had none and commit on its own — the same divergence
 * reached by not saying so.
 *
 * <p><strong>What this transaction does <em>not</em> do is send anything.</strong> A
 * sent email is not rolled back, so the send is a second queue drained afterwards by
 * {@code NotificationDispatch}. That is the whole reason a notification has a state.
 *
 * <h2>Redelivery</h2>
 *
 * <p>At-least-once is {@code OutboxMessage}'s stated contract rather than an edge case,
 * so the first thing this does is ask whether the event has already been fanned out.
 * {@code notifications_event_recipient_channel_key} is what makes the answer true under
 * concurrency rather than merely usually right: two relays that both got past the check
 * meet on the unique index and the second one fails, which rolls its dispatch back and
 * leaves the event to be tried once more — at which point the check succeeds.
 */
@Service
public class NotificationFanOut {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanOut.class);

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final ObjectMapper json;
    private final Clock clock;

    public NotificationFanOut(
            NotificationRepository notifications,
            NotificationPreferenceRepository preferences,
            ObjectMapper json,
            Clock clock) {

        this.notifications = notifications;
        this.preferences = preferences;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Turns one delivered event into the notifications it owes.
     *
     * @param eventId the delivery this is being written from — {@code OutboxMessage.id()},
     *     which is stable across every redelivery of the same event and is what the rows
     *     are unique on
     * @param requests who to tell and what. Empty is allowed and does nothing: an event
     *     this module recognises but which turns out to concern nobody is not an error
     * @return the notifications written, which is empty both when the event was already
     *     fanned out and when every channel was refused by a preference. The caller is a
     *     listener with nothing to do either way; the distinction is in the log
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Notification> fanOut(UUID eventId, List<NotificationRequest> requests) {
        Objects.requireNonNull(eventId, "A notification names the delivery it came from");
        Objects.requireNonNull(requests, "There is nothing to fan out");

        if (notifications.existsByEventId(eventId)) {
            // The ordinary redelivery. Debug rather than warn: it is the contract
            // working, not a fault.
            log.debug("Event {} has already been fanned out; this delivery changes nothing", eventId);
            return List.of();
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // One read of the recipient's instructions per recipient, not per channel. An
        // event with several recipients is rare and an event with several channels per
        // recipient is every event, so the cache is what keeps this at one query for the
        // common case.
        Map<UUID, Map<NotificationCategoryChannel, DeliveryMode>> stored = new HashMap<>();
        List<Notification> written = new ArrayList<>();

        for (NotificationRequest request : requests) {
            Map<NotificationCategoryChannel, DeliveryMode> instructions =
                    stored.computeIfAbsent(request.recipientId(), this::instructionsFor);
            written.addAll(fanOut(eventId, request, instructions, now));
        }

        if (written.isEmpty() && !requests.isEmpty()) {
            // Worth a line: "the preferences said no to everything" and "the translation
            // produced nothing" look identical in the table, and only one of them is a
            // question anybody would ask about a bug report.
            log.debug("Event {} produced no notifications: every channel was refused by a preference", eventId);
        }
        return List.copyOf(notifications.saveAll(written));
    }

    /**
     * One request, across the channels §4.10 gives its type.
     *
     * <p>{@link DeliveryMode#OFF} writes nothing at all — not a suppressed row. A record
     * of everything the platform decided not to tell somebody is a log of their reading
     * habits with a retention policy nobody has argued for, and it would be the largest
     * table in the schema.
     */
    private List<Notification> fanOut(
            UUID eventId,
            NotificationRequest request,
            Map<NotificationCategoryChannel, DeliveryMode> instructions,
            Instant now) {

        NotificationType type = request.type();
        String params = serialise(request);
        List<Notification> written = new ArrayList<>();

        for (NotificationChannel channel : NotificationChannel.values()) {
            // Iterating the enum rather than type.channels() so that the order is
            // stable: a Set.of() has none, and a test asserting on what was written
            // should not depend on a hash.
            if (!type.supports(channel)) {
                continue;
            }
            DeliveryMode mode = DeliveryPolicy.resolve(
                    type, channel, instructions.get(new NotificationCategoryChannel(type.category(), channel)));

            switch (mode) {
                case OFF -> {
                    // Honoured by absence. See above.
                }
                case IMMEDIATE -> written.add(Notification.pending(
                        request.recipientId(),
                        type,
                        channel,
                        eventId,
                        request.subjectType(),
                        request.subjectId(),
                        params,
                        request.occurredAt(),
                        now));
                case DIGEST -> written.add(Notification.held(
                        request.recipientId(),
                        type,
                        channel,
                        eventId,
                        request.subjectType(),
                        request.subjectId(),
                        params,
                        request.occurredAt(),
                        now));
            }
        }
        return written;
    }

    /** What this person has said, as a map the resolution can miss in. */
    private Map<NotificationCategoryChannel, DeliveryMode> instructionsFor(UUID recipientId) {
        Map<NotificationCategoryChannel, DeliveryMode> instructions = new HashMap<>();
        for (NotificationPreference preference : preferences.findByUserId(recipientId)) {
            instructions.put(
                    new NotificationCategoryChannel(preference.getCategory(), preference.getChannel()),
                    preference.getDeliveryMode());
        }
        return instructions;
    }

    /**
     * The rendering document, as JSON.
     *
     * <p>Written with the application's own {@code ObjectMapper}, so that money in it is
     * subject to §10.3's rules — an amount as a string, never a JSON number — without
     * this class knowing that is what it is asking for. The same argument
     * {@code ReferralAttributionListener} makes about reading one.
     */
    private String serialise(NotificationRequest request) {
        try {
            return json.writeValueAsString(request.params());
        } catch (JacksonException unserialisable) {
            // Unchecked in Jackson 3, and still caught, for Outbox's reason: a raw
            // databind error surfacing from the middle of a dispatch says nothing about
            // which notification it was about. It must fail — a document nobody can
            // write is a notification nobody can render — but it must fail saying so.
            throw new IllegalStateException(
                    "The parameters of a " + request.type() + " notification could not be serialised",
                    unserialisable);
        }
    }

    /**
     * The key a preference is looked up by.
     *
     * <p>A record rather than nested maps, because the lookup is always by both halves
     * at once and a map of maps would be two absent-value cases to get right instead of
     * one.
     */
    private record NotificationCategoryChannel(NotificationCategory category, NotificationChannel channel) {
    }
}
