package az.ideanest.notification.application;

import az.ideanest.notification.domain.NotificationType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One person, one thing to tell them — before any channel has been decided.
 *
 * <p>The unit a translation produces and the unit {@code NotificationFanOut} consumes.
 * The fan-out from here to zero or more {@code Notification} rows is exactly the
 * preference resolution: §4.10 says which channels the type has, the recipient's
 * standing instructions say what to do on each, and {@code DeliveryPolicy} answers for
 * the ones they have never said anything about.
 *
 * <p><strong>The recipient comes from the event, and that is a real constraint on what
 * this module can do.</strong> A translation may not look up who ought to be told: the
 * backers of a campaign are in {@code pledges} and its followers are in
 * {@code project_reminders}, both of which belong to other modules, and reading them
 * from here is precisely the coupling {@code ModuleBoundaryTests} exists to prevent.
 * So an event whose audience is a list the platform has to compute cannot be translated
 * until either the producer carries the list or somebody publishes an audience port —
 * and {@code NotificationEventListener} says which events those are.
 *
 * @param recipientId who is being told. One person: a request is not a broadcast, and
 *     an event with several recipients is several requests
 * @param type which of §4.10's rows this is. It carries the category the preference is
 *     looked up under and the channels the fan-out may consider
 * @param subjectType what it is about — {@code project}, {@code pledge} — or null
 * @param subjectId which one, or null. Whole or absent with the type above
 * @param params what a template will need and cannot look up: a campaign's title as it
 *     was, an amount that was pledged. <strong>Money goes in here as a
 *     {@code shared.money.Money}</strong> and is serialised by the application's own
 *     {@code ObjectMapper}, so §10.3's rule — an amount as a string, never a JSON number
 *     — applies without any caller having to remember it
 * @param occurredAt when the reported thing happened, from the event. Not the clock:
 *     an event redelivered an hour late still describes something an hour old, and the
 *     inbox is ordered by this
 */
public record NotificationRequest(
        UUID recipientId,
        NotificationType type,
        String subjectType,
        UUID subjectId,
        Map<String, Object> params,
        Instant occurredAt) {

    public NotificationRequest {
        Objects.requireNonNull(recipientId, "A notification is told to somebody");
        Objects.requireNonNull(type, "A notification is of some kind");
        Objects.requireNonNull(occurredAt, "Something happened at some moment");
        if ((subjectType == null) != (subjectId == null)) {
            // notifications_subject_is_whole, refused here so that the failure names the
            // translation that built it rather than the insert.
            throw new IllegalArgumentException("A subject is a type and an identifier, or it is neither");
        }
        // Copied and never null, so that a template's document is always an object and a
        // translation cannot hand over a map it goes on to mutate.
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** A request about a campaign. */
    public static NotificationRequest about(
            UUID recipientId,
            NotificationType type,
            String subjectType,
            UUID subjectId,
            Map<String, Object> params,
            Instant occurredAt) {

        return new NotificationRequest(recipientId, type, subjectType, subjectId, params, occurredAt);
    }
}
