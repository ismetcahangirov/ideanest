package az.ideanest.notification.application;

import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Several notifications for one person on one channel, as the one message that carries them.
 *
 * <p>What §12.2's "a scheduled job combines them into a single message" produces.
 * {@link NotificationMessage} is one notification and this is a set of them; both are records
 * rather than entities for the same reason — a transport adapter, and later an out-of-process
 * one, must not be able to reach the rows, change their state, or hold a managed instance
 * past the transaction that loaded it.
 *
 * <h2>The idempotency key is derived from the members</h2>
 *
 * <p>{@link NotificationMessage#id()} is the notification's own primary key, stable across
 * every attempt, and a digest has no such column: it is not a row, it is a grouping computed
 * on the way out. So {@link #id()} is a name-based UUID over the sorted identifiers of the
 * notifications in it. Two consequences, and the second one is the honest limitation:
 *
 * <ul>
 *   <li><strong>A retry of the same digest carries the same key.</strong> That is the whole
 *       requirement {@link ChannelSender} states — the send happens before the transaction
 *       recording it commits, so a crash in between sends again, and what makes the duplicate
 *       collapsible is a key a provider can deduplicate on. Generating a fresh identifier per
 *       attempt would make this module's central claim false for digests specifically, and
 *       nothing else would notice.
 *   <li><strong>A digest whose membership changed is a different message, and gets a different
 *       key.</strong> It can change: a late-delivered event can add a held row whose
 *       {@code occurred_at} is inside a period that has already closed. Under the pass's
 *       bound it can also change because the previous attempt claimed a different slice. In
 *       both cases the key differing is truthful rather than a defect — the two messages have
 *       different contents, so collapsing them would drop notifications somebody was owed,
 *       which is the failure this module treats as its worst.
 * </ul>
 *
 * <p>Version 3 rather than 7 for this one value, and deliberately not {@code Identifiers}: a
 * digest's key has to be a <em>function of its contents</em>, and every identifier that
 * package makes is a function of the clock. It is not a primary key and is never stored.
 *
 * @param id the idempotency key — see above. Not a row identifier, and there is no row
 * @param recipientId who is being told. An identifier and not an address: resolving one to a
 *     mailbox or a device token is the transport's problem, which is #86's and #87's
 * @param channel where it is going. Never {@link NotificationChannel#IN_APP} —
 *     {@code notifications_in_app_is_not_held} makes an in-app digest impossible to store,
 *     because an inbox is already a list
 * @param notifications what is in it, oldest first, so that a rendered digest reads in the
 *     order the things happened. Never empty: a digest of nothing is not a message
 * @param from the {@code occurredAt} of the oldest notification in it
 * @param to the {@code occurredAt} of the newest. With {@link #from} it is the period a
 *     template can describe — "here is what happened since yesterday" needs both ends, and
 *     neither is the instant the send was attempted
 */
public record NotificationDigest(
        UUID id,
        UUID recipientId,
        NotificationChannel channel,
        List<NotificationMessage> notifications,
        Instant from,
        Instant to) {

    /** ASCII unit separator. Cannot occur in a UUID's text, so no two sets share a string. */
    private static final char MEMBER_SEPARATOR = (char) 0x1f;

    public NotificationDigest {
        Objects.requireNonNull(id, "A digest carries an idempotency key");
        Objects.requireNonNull(recipientId, "A digest is told to somebody");
        Objects.requireNonNull(channel, "A digest goes somewhere");
        Objects.requireNonNull(notifications, "A digest is made of notifications");
        Objects.requireNonNull(from, "A digest covers a period");
        Objects.requireNonNull(to, "A digest covers a period");

        if (notifications.isEmpty()) {
            throw new IllegalArgumentException("A digest of nothing is not a message");
        }
        notifications = List.copyOf(notifications);
    }

    /**
     * The digest for these rows.
     *
     * <p>Sorted here rather than relying on the query, so that the key is a function of the
     * set and not of the order it arrived in — two passes that claimed the same rows in a
     * different order must produce the same key or the deduplication above is worthless.
     *
     * @param notifications the held rows being combined, in any order. All for one recipient
     *     on one channel; a mixed list is a programming error and is refused as one
     */
    public static NotificationDigest of(List<Notification> notifications) {
        Objects.requireNonNull(notifications, "A digest is made of notifications");
        if (notifications.isEmpty()) {
            throw new IllegalArgumentException("A digest of nothing is not a message");
        }

        UUID recipientId = notifications.get(0).getRecipientId();
        NotificationChannel channel = notifications.get(0).getChannel();
        for (Notification notification : notifications) {
            if (!recipientId.equals(notification.getRecipientId()) || channel != notification.getChannel()) {
                // The claim query groups by both, so reaching this means the grouping was
                // lost somewhere between the query and here. Refused loudly rather than
                // sent, because the message it would produce is one person's notifications
                // delivered to another.
                throw new IllegalArgumentException("A digest is one recipient's, on one channel");
            }
        }

        List<Notification> ordered = notifications.stream()
                .sorted(Comparator.comparing(Notification::getOccurredAt).thenComparing(Notification::getId))
                .toList();

        return new NotificationDigest(
                keyOf(ordered),
                recipientId,
                channel,
                ordered.stream().map(NotificationMessage::of).toList(),
                ordered.get(0).getOccurredAt(),
                ordered.get(ordered.size() - 1).getOccurredAt());
    }

    /** How many notifications this digest combines. */
    public int size() {
        return notifications.size();
    }

    /**
     * A key that is a function of the members, and of nothing else.
     *
     * <p>The identifiers, sorted, joined, hashed. Sorted because the set is what identifies
     * the message; joined with a separator that cannot occur in a UUID's text, so that no two
     * different sets can produce one string.
     */
    private static UUID keyOf(List<Notification> ordered) {
        StringBuilder members = new StringBuilder();
        ordered.stream()
                .map(Notification::getId)
                .map(UUID::toString)
                .sorted()
                .forEach(id -> members.append(id).append(MEMBER_SEPARATOR));
        return UUID.nameUUIDFromBytes(members.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        // No recipient and no contents. A digest is several people's worth of one person's
        // business (§17.4) and this record ends up in log lines about failed sends.
        return "NotificationDigest[id=" + id + ", channel=" + channel + ", size=" + notifications.size() + "]";
    }
}
