package az.ideanest.notification.application;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.infrastructure.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.2's {@code GET /v1/me/notifications}: reading one's own in-app inbox, and marking
 * something in it as read.
 *
 * <p><strong>In-app only, and that is not a filter this class applies.</strong> It is in
 * the queries — {@code NotificationRepository.inbox} and {@code countUnread} both name
 * {@code IN_APP} — because an inbox that could serve email rows would be showing somebody
 * a copy of their mail, and {@code notifications_only_the_inbox_is_read} means the read
 * stamp does not even exist on the other two channels.
 *
 * <p><strong>{@code SENT} only, likewise.</strong> A {@code PENDING} row is one the sender
 * has not reached yet and a {@code DEAD} one is a delivery that failed; showing either
 * would put a message in an inbox before, or instead of, the platform deciding it was
 * deliverable. The delay that costs is one sender pass — a second, by default, which
 * {@code InAppChannelSender} argues is worth the uniformity.
 *
 * <p><strong>A {@code HELD} row is not in the inbox either, and cannot be.</strong>
 * {@code notifications_in_app_is_not_held} forbids the combination outright: a digest of
 * an inbox would be a list combined into a list.
 *
 * <h2>Paging</h2>
 *
 * <p>Keyset, per §10.3 and for the reason {@code CommentListResponse} gives more
 * urgently: an inbox is written to while it is being read, so an offset would skip a
 * notification every time one arrived mid-page — and here the row that gets skipped is a
 * message somebody was owed. One extra row is read per page and dropped, which is how the
 * cursor is known to be the last one rather than guessed at from a count.
 */
@Service
public class NotificationInbox {

    private final NotificationRepository notifications;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationInbox(
            NotificationRepository notifications, NotificationProperties properties, Clock clock) {
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One page of this account's inbox, newest first, with the unread count beside it.
     *
     * @param recipientId the authenticated caller. Never a value from a request body:
     *     it is the whole of the authorisation on this read
     * @param before the {@code occurredAt} of the last row of the previous page, or null
     *     for the first page
     * @param beforeId the identifier of that same row. Whole or absent with {@code before}
     * @param limit how many rows to return, or null for the configured default
     */
    @Transactional(readOnly = true)
    public NotificationPage read(UUID recipientId, Instant before, UUID beforeId, Integer limit) {
        Objects.requireNonNull(recipientId, "An inbox belongs to somebody");
        int size = pageSize(limit);
        if ((before == null) != (beforeId == null)) {
            throw new InboxQueryInvalidException(
                    "A cursor is both before and beforeId, or it is neither.",
                    before == null ? "before" : "beforeId",
                    Map.of());
        }

        // One more than asked for. The extra row is the answer to "is there another
        // page", and it is a cheaper answer than a count over everything older than
        // the cursor — which is the query an inbox would run on every request forever.
        PageRequest window = PageRequest.of(0, size + 1);
        List<Notification> page = before == null
                ? notifications.inbox(recipientId, window)
                : notifications.inboxBefore(recipientId, before, beforeId, window);
        long unread = notifications.countUnread(recipientId);

        if (page.size() <= size) {
            return NotificationPage.last(page, unread);
        }
        List<Notification> requested = page.subList(0, size);
        Notification last = requested.get(size - 1);
        return new NotificationPage(requested, last.getOccurredAt(), last.getId(), unread);
    }

    /**
     * Records that the recipient opened one of their notifications.
     *
     * <p><strong>Idempotent, and the entity is where that lives.</strong>
     * {@code Notification.markRead} keeps the first instant, so a client that re-renders
     * a list does not move the stamp to whenever it last drew the row — which is not when
     * anybody read anything. A second call therefore succeeds and changes nothing, which
     * is also what makes this safe to retry.
     *
     * <p>Returns the notification rather than nothing, so the client has the row to
     * re-render — including the unread count it should now be drawing, which it gets by
     * reading the inbox again or by decrementing what it already had.
     *
     * @throws NotificationNotFoundException when there is no such row, or it is somebody
     *     else's. One answer for both, on purpose — see that class
     */
    @Transactional
    public Notification markRead(UUID recipientId, UUID notificationId) {
        Objects.requireNonNull(recipientId, "A notification is read by somebody");
        Objects.requireNonNull(notificationId, "Some notification was read");

        Notification notification = notifications
                .findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new NotificationNotFoundException("There is no such notification."));

        if (notification.getChannel() != NotificationChannel.IN_APP || notification.getSentAt() == null) {
            // notifications_only_the_inbox_is_read would refuse the write anyway. Refused
            // here so that the caller gets the module's 404 rather than a constraint
            // violation, and so that an email row is not distinguishable from a
            // non-existent one by the shape of the failure.
            throw new NotificationNotFoundException("There is no such notification.");
        }

        notification.markRead(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return notification;
    }

    /**
     * How many rows one request may have.
     *
     * <p>Refused rather than clamped when it is over the ceiling.
     * {@link InboxQueryInvalidException} argues why, and the ceiling travels in the
     * refusal.
     */
    private int pageSize(Integer limit) {
        NotificationProperties.Inbox inbox = properties.inbox();
        if (limit == null) {
            return inbox.defaultPageSize();
        }
        if (limit < 1 || limit > inbox.maxPageSize()) {
            throw new InboxQueryInvalidException(
                    "A page holds between 1 and " + inbox.maxPageSize() + " notifications.",
                    "limit",
                    Map.of("minLimit", 1, "maxLimit", inbox.maxPageSize()));
        }
        return limit;
    }
}
