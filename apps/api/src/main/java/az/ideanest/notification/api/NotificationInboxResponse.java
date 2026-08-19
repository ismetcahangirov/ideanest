package az.ideanest.notification.api;

import az.ideanest.notification.application.NotificationPage;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of somebody's inbox, the position to continue from, and the badge number.
 *
 * <p>Cursor based per §10.3, for the reason {@code CommentListResponse} gives: an offset
 * over a list that is written to while it is being read skips a row every time one
 * arrives mid-page, and here the row that gets skipped is a message somebody was owed.
 *
 * <p><strong>The cursor is two values, and they are not obfuscated.</strong>
 * {@code CommentListResponse} makes the argument — a client already holds both for every
 * row on the page, so an opaque token would only need decoding — and here it is a pair
 * rather than one identifier because the ordering is {@code (occurredAt, id)}:
 * {@code occurred_at} comes from the event, so two notifications fanned out from one event
 * share it, and an instant alone would either serve one twice or skip the other.
 *
 * <p><strong>There is no ETag on this response, unlike the public lists.</strong> Those are
 * cacheable reads of something many people see. This is one account's inbox, it changes
 * whenever anything happens to them, and {@link #unreadCount} changes again the moment
 * they read a row — so a validator would almost never match, and the request that computed
 * it would have done all the work anyway. §10.3 asks for revalidation where it saves work;
 * here it would only add a header.
 *
 * @param notifications the rows, newest first. Empty for an account nobody has told
 *     anything, which is an ordinary answer and not a missing one
 * @param nextCursor the {@code occurredAt} to send as {@code ?before=} for the next page,
 *     or null when this is the last one. Null rather than a count of what remains: a count
 *     is a second aggregate over rows nobody has asked for, and a client only needs to know
 *     whether there is more and what to ask for
 * @param nextCursorId the identifier to send as {@code ?beforeId=} with it, or null. Whole
 *     or absent with {@link #nextCursor}
 * @param unreadCount how many in-app notifications this account has not opened, across the
 *     whole inbox rather than this page. The number a badge is drawn from
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationInboxResponse(
        List<NotificationResponse> notifications, Instant nextCursor, UUID nextCursorId, long unreadCount) {

    public static NotificationInboxResponse of(NotificationPage page) {
        return new NotificationInboxResponse(
                page.notifications().stream().map(NotificationResponse::of).toList(),
                page.nextCursor(),
                page.nextCursorId(),
                page.unread());
    }
}
