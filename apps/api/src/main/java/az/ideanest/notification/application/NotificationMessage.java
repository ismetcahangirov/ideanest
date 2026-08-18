package az.ideanest.notification.application;

import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationType;
import java.time.Instant;
import java.util.UUID;

/**
 * One notification, as the thing that sends it sees it.
 *
 * <p>A record rather than the entity, for {@code OutboxMessage}'s reason: a transport
 * adapter — and, later, an out-of-process one — must not be able to reach the row,
 * change its state, or hold a managed instance past the transaction that loaded it.
 * What a sender needs is who, what, and the identity it must be idempotent on.
 *
 * @param id <strong>the idempotency key, and the point of this record.</strong> Stable
 *     across every send attempt of this notification, so a transport that is handed the
 *     same message twice — which will happen, see {@code NotificationDispatch} — can
 *     collapse the two. #86's mail provider and #87's push service must pass it through
 *     as their own idempotency key; a provider that offers none makes duplicate mail
 *     possible, and that is a property of the provider rather than something this side
 *     can fix
 * @param recipientId who is being told. An identifier and not an address: resolving one
 *     to a mailbox or a device token is the transport's own problem, and it is the half
 *     that does not exist yet — there is no device registry (§4.10's note) and the
 *     address is in {@code users}, which belongs to another module
 * @param type which of §4.10's rows this is, which is what selects a template
 * @param channel where it is going. A sender is registered for exactly one, so this is
 *     redundant to the sender itself and useful to everything that logs
 * @param subjectType what it is about — {@code project}, {@code pledge} — or null
 * @param subjectId which one, or null
 * @param params the rendering document, as JSON. Opaque here: this module decides what
 *     goes in it and #86 decides what a template does with it. Money inside it is
 *     §10.3's object with the amount as a string, never a JSON number
 * @param occurredAt when the reported thing happened. Not when the send is being
 *     attempted — a message about something that happened an hour ago should say so
 */
public record NotificationMessage(
        UUID id,
        UUID recipientId,
        NotificationType type,
        NotificationChannel channel,
        String subjectType,
        UUID subjectId,
        String params,
        Instant occurredAt) {

    /** The message for one row. */
    public static NotificationMessage of(Notification notification) {
        return new NotificationMessage(
                notification.getId(),
                notification.getRecipientId(),
                notification.getType(),
                notification.getChannel(),
                notification.getSubjectType(),
                notification.getSubjectId(),
                notification.getParams(),
                notification.getOccurredAt());
    }

    @Override
    public String toString() {
        // No recipient and no params. A notification's body is one person's business
        // (§17.4) and this record ends up in log lines about failed sends.
        return "NotificationMessage[id=" + id + ", type=" + type + ", channel=" + channel + "]";
    }
}
