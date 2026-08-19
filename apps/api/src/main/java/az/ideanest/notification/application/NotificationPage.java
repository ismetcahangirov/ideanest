package az.ideanest.notification.application;

import az.ideanest.notification.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One page of somebody's in-app inbox, and the two things a client needs beside it.
 *
 * <p>The cursor is a pair, because the ordering is. {@code notifications.occurred_at}
 * comes from the event rather than from the clock, so two notifications fanned out from
 * one event share an instant, and a cursor that was only an instant would either skip
 * the second one or serve the first one twice. The identifier breaks the tie, and it can
 * because it is a UUID v7 and therefore itself time-ordered.
 *
 * <p><strong>Both halves are null together or neither is.</strong> That is checked here
 * rather than left to the query, so a page whose cursor cannot be followed is refused
 * where it was built.
 *
 * @param notifications the rows, newest first. Possibly empty, which is an ordinary
 *     answer: an account nobody has told anything has an empty inbox, not a missing one
 * @param nextCursor the {@code occurredAt} to ask below for the next page, or null when
 *     this is the last one
 * @param nextCursorId the identifier of the same row, which is what makes the cursor
 *     total rather than merely usually distinct
 * @param unread how many in-app notifications this account has not opened. The whole
 *     count and not this page's, because it is what a badge is drawn from — a badge
 *     showing "how many unread on the page you are looking at" is a number nobody wants
 */
public record NotificationPage(
        List<Notification> notifications, Instant nextCursor, UUID nextCursorId, long unread) {

    public NotificationPage {
        Objects.requireNonNull(notifications, "A page is some list of notifications, possibly empty");
        if ((nextCursor == null) != (nextCursorId == null)) {
            throw new IllegalArgumentException("A cursor is an instant and an identifier, or it is neither");
        }
        if (unread < 0) {
            throw new IllegalArgumentException("A count of unread notifications is not negative");
        }
        notifications = List.copyOf(notifications);
    }

    /** The last page of an inbox: these rows, and nothing to ask below. */
    public static NotificationPage last(List<Notification> notifications, long unread) {
        return new NotificationPage(notifications, null, null, unread);
    }
}
