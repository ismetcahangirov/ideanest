package az.ideanest.notification.api;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.NotificationPreferences;
import az.ideanest.notification.application.PreferenceChange;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.2's {@code PATCH /v1/me/notification-preferences}, and the read #89 needs to draw the
 * page before anybody changes it.
 *
 * <h2>The {@code GET} is not in §10.2, and is here anyway</h2>
 *
 * <p>Stated plainly, as the read stamp is on {@code NotificationInboxController}. §10.2
 * lists only the {@code PATCH}, and a settings page cannot be rendered from a write: the
 * common case is an account with no stored rows at all — {@code DeliveryPolicy} explains why
 * nothing is seeded at registration — so there is nothing a client could derive the current
 * state from without reimplementing the defaults and then drifting from them. The
 * alternative was to make the {@code PATCH} with an empty list the way to read, which works
 * and is what {@code NotificationPreferences.apply} does, but a page load that sends a write
 * to find out what it is showing is a route that cannot be cached, cannot be given to a
 * read-only client, and spends the write budget below. §10.2 has been extended in the same
 * change.
 *
 * <h2>Rate limiting</h2>
 *
 * <p>On the {@code PATCH} only, and here rather than in the service — following
 * {@code CommentController} and {@code AuthController}: it is about the transport, and
 * {@code NotificationPreferences} should not have to know it is being reached over HTTP.
 *
 * <p><strong>Per account and not per address.</strong> {@code NotificationProperties.RateLimit}
 * argues it: the request carries a token, so an attacker holding one is not constrained by
 * where they come from, and limiting by address would instead punish everybody behind one
 * NAT. The budget is generous because a settings page that saves one switch at a time
 * legitimately sends several in a row; what matters is that there is a ceiling, not where it
 * is.
 *
 * <p><strong>The {@code GET} is not limited.</strong> It is what a page does on load, it
 * writes nothing, and the work is one indexed read of at most twenty-one rows.
 *
 * <p>Whose preferences these are is the access token on both endpoints. There is no account
 * in the path and none in the body — {@code UpdateNotificationPreferencesRequest} says why
 * that field would be the only one that mattered.
 */
@RestController
public class NotificationPreferenceController {

    private final NotificationPreferences preferences;
    private final RateLimiter rateLimiter;
    private final NotificationProperties properties;

    public NotificationPreferenceController(
            NotificationPreferences preferences, RateLimiter rateLimiter, NotificationProperties properties) {
        this.preferences = preferences;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * The whole settings page for this account.
     *
     * <p>Every switch §4.10 has, resolved through the same policy the fan-out uses — see
     * {@code NotificationPreferencesResponse} for why it is never only the stored rows.
     */
    @GetMapping("/v1/me/notification-preferences")
    public NotificationPreferencesResponse preferences(@AuthenticationPrincipal Jwt accessToken) {
        return NotificationPreferencesResponse.of(preferences.all(callerOf(accessToken)));
    }

    /**
     * Sets some switches, and answers with the whole page.
     *
     * <p>The whole page rather than what changed, because a change can move something the
     * caller did not send and a client should not have to reimplement
     * {@code DeliveryPolicy} to find out what it now has.
     *
     * <p><strong>All of it or none of it.</strong> {@code NotificationPreferences.apply}
     * checks every instruction before it writes any, so a request with a refused switch in
     * the middle leaves nothing saved — a person who pressed one button and saw one error
     * would otherwise have no way to know that part of their change had landed.
     *
     * <p>Refusals are §10.4 problem details: 422 for a mandatory category and for a digest
     * on a channel that cannot digest, 409 for two requests writing one switch at once. See
     * {@link NotificationExceptionHandler}.
     */
    @PatchMapping("/v1/me/notification-preferences")
    public NotificationPreferencesResponse update(
            @AuthenticationPrincipal Jwt accessToken,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {

        UUID userId = callerOf(accessToken);
        spendUpdateBudget(userId);
        return NotificationPreferencesResponse.of(preferences.apply(userId, changesOf(request)));
    }

    /**
     * Counts this write against the account's budget, before anything is stored.
     *
     * <p>Before, and not after: a limit that is checked once the row is written is a limit
     * on the response rather than on the work.
     */
    private void spendUpdateBudget(UUID userId) {
        NotificationProperties.RateLimit limits = properties.rateLimit();
        RateLimits.enforce(rateLimiter.recordAttempt(
                "notification-preferences:account:" + userId, limits.preferenceUpdatesPerUser(), limits.window()));
    }

    /**
     * The instructions, defended against a body that bound to nothing.
     *
     * <p>{@code @NotNull} on the field already refuses an absent list with a 400, so this is
     * the second line rather than the first — and it is here because a body of the four
     * characters {@code null} binds the whole record to null before any constraint on a
     * field inside it is evaluated.
     */
    private static List<PreferenceChange> changesOf(UpdateNotificationPreferencesRequest request) {
        return request == null || request.preferences() == null ? List.of() : request.changes();
    }

    /**
     * The account making the request, as our own signature establishes it.
     *
     * <p>Not read from anything the caller could choose. It is the whole of the
     * authorisation on both endpoints: it decides whose settings are read and whose are
     * rewritten.
     */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
