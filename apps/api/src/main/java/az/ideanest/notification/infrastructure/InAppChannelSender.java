package az.ideanest.notification.infrastructure;

import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.application.NotificationDigest;
import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The in-app inbox, which needs no transport because the row is the message.
 *
 * <p><strong>This method does nothing, and that is the complete and correct
 * implementation</strong> — not a placeholder like {@code UndeliverableChannelSender}
 * beside it. §10.2's {@code GET /v1/me/notifications} reads {@code notifications}
 * directly, so "delivering" an in-app notification is the row existing and being
 * {@code SENT}, and the stamp that makes it so is written by
 * {@code NotificationDispatch} in the same transaction as this call. There is nothing
 * left for a sender to do.
 *
 * <p>Two consequences worth stating rather than discovering:
 *
 * <ul>
 *   <li><strong>In-app is the one channel that cannot double-send.</strong> Everything
 *       the recipient sees is one row, keyed on (event, recipient, channel), so a
 *       redelivered event produces nothing and a re-sent message overwrites its own
 *       stamp. The at-least-once caveat that {@link ChannelSender} spends a paragraph
 *       on is real for email and push and vacuous here.
 *   <li><strong>It is also the one channel with latency it did not need.</strong> The
 *       row is written by the fan-out and becomes visible one sender pass later —
 *       a second, by default. Uniformity was worth that: one queue, one retry policy,
 *       and one place where a channel is added, rather than an in-app path that writes
 *       {@code SENT} directly and an email path that does not, differing in the one
 *       respect that is hardest to test.
 * </ul>
 *
 * <p>§12.1's real-time push to the {@code user:{id}} channel — telling a connected
 * client that its inbox changed without it asking — is where this class would gain a
 * body. There is no WebSocket gateway in the service yet, and it is not #85's.
 */
@Component
public class InAppChannelSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(InAppChannelSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void send(NotificationMessage message) {
        // Debug, not info: this runs once per in-app notification on the platform, and
        // it records nothing that is not already a row somebody can query.
        log.debug("{} is in the inbox", message);
    }

    /**
     * Refuses, because an in-app digest cannot exist.
     *
     * <p>Not a no-op like the method above it, and not a loop over the members. Two
     * constraints forbid the combination outright —
     * {@code notification_preferences_in_app_does_not_digest} refuses the preference and
     * {@code notifications_in_app_is_not_held} refuses the row — and {@code Notification.held}
     * refuses to construct one, so there is no path on which this can be called. A body that
     * did something plausible would be a body that made the unreachable case behave, which
     * is how an unreachable case becomes reachable without anybody noticing.
     */
    @Override
    public void send(NotificationDigest digest) {
        throw new IllegalStateException("An inbox is already a list; " + digest + " should not exist");
    }
}
