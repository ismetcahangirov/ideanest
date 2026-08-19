package az.ideanest.notification.api;

import az.ideanest.notification.application.NotificationInbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.2's {@code GET /v1/me/notifications}, and the read stamp that goes with it.
 *
 * <p>The half of #85 that was built and had no address. The rows, the fan-out, the delivery
 * loop and the read column all existed; nothing could ask for them over HTTP, which is why
 * #88's inbox had nothing to call.
 *
 * <h2>{@code POST /v1/me/notifications/{id}/read} is not in §10.2, and is here anyway</h2>
 *
 * <p>Stated plainly rather than slipped in. §10.2 lists one notification endpoint and
 * {@code notifications.read_at}, {@code Notification.markRead} and
 * {@code notifications_unread_idx} were all built by #85 with nothing that could write
 * them. An inbox that reports an unread count and offers no way to stop being unread is the
 * same failure #244 is about in the other direction: a surface that describes something the
 * platform will not do. §10.2 has been extended in the same change, per the repository's
 * rule that documentation moves with the code.
 *
 * <p><strong>{@code POST} rather than {@code PATCH}.</strong> Reading is not an edit to a
 * field a client may choose a value for — there is one instant it can be set to and the
 * server owns it — so a body describing the change would be a body with nothing in it that
 * could differ. It is closer to {@code POST /v1/me/deletion} than to {@code PATCH /v1/me}.
 *
 * <p><strong>No rate limit on either.</strong> {@code NotificationProperties.RateLimit}
 * budgets the one write this module exposes and it is the preference update, not this.
 * Reading an inbox is what a client does on every page load, and marking a row read is
 * idempotent on a row the caller already owns — a limit on it would mean somebody clearing
 * a backlog of notifications being stopped part way through, which is
 * {@code CommentController}'s argument for not limiting deletions.
 *
 * <p>Authorisation is the token and nothing else, and it is complete: every query in
 * {@code NotificationInbox} is keyed on the recipient, so there is no path through this
 * controller on which one account can name another.
 */
@RestController
public class NotificationInboxController {

    private final NotificationInbox inbox;

    public NotificationInboxController(NotificationInbox inbox) {
        this.inbox = inbox;
    }

    /**
     * One page of this account's in-app inbox, newest first.
     *
     * <p>The two cursor parameters are the two halves of one position and must arrive
     * together; half of one is a 400 naming the missing half. See
     * {@code NotificationInboxResponse} for why the cursor is a pair and why it is not
     * opaque.
     *
     * @param before the {@code occurredAt} of the last row of the previous page, as an ISO
     *     8601 instant. {@code @DateTimeFormat(ISO.DATE_TIME)} rather than a converter,
     *     because what the client is echoing back is exactly what Jackson wrote into
     *     {@code nextCursor}
     * @param beforeId the identifier of that same row
     * @param limit how many rows, or absent for the configured default. A value outside the
     *     configured bounds is refused rather than clamped —
     *     {@code InboxQueryInvalidException} argues why, and the ceiling comes back in the
     *     refusal
     */
    @GetMapping("/v1/me/notifications")
    public NotificationInboxResponse notifications(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(required = false) Integer limit) {

        return NotificationInboxResponse.of(inbox.read(callerOf(accessToken), before, beforeId, limit));
    }

    /**
     * Records that the caller has opened one of their notifications.
     *
     * <p><strong>200 with the row, not 204.</strong> The client has something to re-render
     * — the row now carries {@code readAt} — and a 204 would leave it either guessing the
     * instant or re-reading the whole page to draw one dot.
     *
     * <p>Idempotent, and the first instant is the one kept: {@code Notification.markRead}
     * is where that lives, because a client that re-renders a list must not be able to move
     * the stamp to whenever it last drew the row. So a double tap and a retry are both
     * harmless.
     *
     * <p>A notification that is not this account's, and one that does not exist, both
     * answer 404 — {@code NotificationNotFoundException} says why they must not be
     * distinguishable.
     */
    @PostMapping("/v1/me/notifications/{notificationId}/read")
    public NotificationResponse read(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID notificationId) {

        return NotificationResponse.of(inbox.markRead(callerOf(accessToken), notificationId));
    }

    /**
     * The account making the request, as our own signature establishes it.
     *
     * <p>Not read from anything the caller could choose. On these two endpoints it is the
     * whole of the authorisation: it decides whose inbox is served and whose notification
     * may be marked read.
     */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
