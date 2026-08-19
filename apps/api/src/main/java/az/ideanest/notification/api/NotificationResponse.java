package az.ideanest.notification.api;

import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of somebody's inbox.
 *
 * <p><strong>Nulls are written out.</strong> A notification about nothing in particular
 * answers {@code "subjectType": null} rather than omitting the key, so a client does not
 * have to tell "absent" from "not sent" — {@code CommentResponse} and every other list row
 * in the service make the same choice.
 *
 * <p><strong>There is no delivery state here, and there should not be.</strong> The row's
 * {@code state}, {@code attempts} and {@code lastError} are the platform's business:
 * "attempt three of eight failed" is an operational fact about a transport, and putting it
 * in an inbox would invite a client to render it. The one delivery fact a reader needs is
 * that the notification is in their inbox at all, which it is by virtue of being in this
 * list — {@code NotificationInbox} serves {@code SENT} in-app rows and nothing else.
 *
 * @param type which of §4.10's rows this is. The client's key for the template it renders,
 *     and the reason the rendering document below can stay opaque
 * @param category the grouping the preference for it is expressed under. On the row so a
 *     client can offer "stop telling me about this kind of thing" from the inbox rather
 *     than making somebody find the category on the settings page
 * @param subjectType what it is about — {@code project}, {@code pledge} — or null
 * @param subjectId which one, or null. Whole or absent with the type
 * @param params what the template needs, verbatim. <strong>Emitted raw rather than parsed
 *     and re-serialised</strong>, which is not an optimisation: the document is a
 *     {@code jsonb} column this module wrote and never reads inside, and money in it is
 *     §10.3's object with the amount as a string. Round-tripping it through a
 *     {@code Map<String, Object>} would put every one of those amounts through a decoder
 *     for no reason, and the one decoder that must never touch them is the one that could
 *     turn {@code "25.00"} into a double
 * @param occurredAt when the reported thing happened, from the event rather than from the
 *     clock — which is why the inbox is ordered by it. A notification produced from an
 *     event redelivered an hour late still describes something an hour old, and sorting by
 *     when the row was written would put it at the top
 * @param readAt when this account opened it, or null. The flag a client draws the unread
 *     dot from, and an instant rather than a boolean because it is the same column the
 *     platform stores — a boolean here would be a second representation to keep in step
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationResponse(
        UUID id,
        NotificationType type,
        NotificationCategory category,
        String subjectType,
        UUID subjectId,
        @JsonRawValue String params,
        Instant occurredAt,
        Instant readAt) {

    /** An empty rendering document, for the row that somehow has none. */
    private static final String NO_PARAMS = "{}";

    public static NotificationResponse of(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getCategory(),
                notification.getSubjectType(),
                notification.getSubjectId(),
                // notifications.params is NOT NULL and defaults to {}, and
                // notifications_params_is_an_object keeps it an object. Defended anyway,
                // because the failure this prevents is a response body that is not JSON
                // at all — one null here would break the whole page rather than one row.
                notification.getParams() == null || notification.getParams().isBlank()
                        ? NO_PARAMS
                        : notification.getParams(),
                notification.getOccurredAt(),
                notification.getReadAt());
    }
}
