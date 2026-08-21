package az.ideanest.community.api;

import az.ideanest.community.CommunityProperties;
import az.ideanest.community.application.BackerSignalService;
import az.ideanest.community.application.SignalCursor;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.9's C-09 and C-10 over HTTP: saving a campaign, following an account, and the two lists
 * that result.
 *
 * <p><strong>One controller across three path prefixes</strong>, which is unusual here and is
 * the right shape for once. {@code CommentController} already declares full paths per method
 * rather than a class-level prefix, so the mechanism is the established one; what is new is
 * that these five endpoints genuinely span {@code /v1/projects}, {@code /v1/users} and
 * {@code /v1/me}. Splitting them by prefix would put one feature in three files that share a
 * service, a rate limit and an exception advice — and the reader looking for "where is
 * following handled" would have to find two of them.
 *
 * <p><strong>Every endpoint here requires a bearer token</strong>, by falling through to
 * {@code SecurityConfiguration}'s catch-all rather than being named in it. That is the whole
 * of the authorisation: there is no capability involved in saving something, and whether the
 * target may be seen at all is decided in {@code BackerSignalService} by asking the module
 * that owns it.
 *
 * <p><strong>Rate limiting is here rather than in the service</strong>, following
 * {@code CommentController} and {@code AuthController}: it is about the transport — a 429 with
 * {@code Retry-After} — and the service should not have to know it is being reached over HTTP.
 * The budget is per account and it is spent by the four writes and not by the two reads, for
 * the reason {@code CommunityProperties.Signals} gives.
 *
 * <p><strong>The writes return the resulting state rather than 204.</strong> A toggle whose
 * response body says {@code saved: true} lets a client render from the answer instead of from
 * what it assumed it did, which matters precisely because these calls are idempotent: a retry
 * after a dropped connection is indistinguishable from the first attempt, and both are right.
 */
@RestController
public class BackerSignalController {

    private final BackerSignalService signals;
    private final RateLimiter rateLimiter;
    private final CommunityProperties properties;

    public BackerSignalController(
            BackerSignalService signals, RateLimiter rateLimiter, CommunityProperties properties) {
        this.signals = signals;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** "Save this campaign." §10.2's {@code POST /v1/projects/{id}/save}, and C-09. */
    @PostMapping("/v1/projects/{projectId}/save")
    public SaveStateResponse save(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {
        UUID accountId = callerOf(accessToken);
        limit(accountId);
        signals.save(projectId, accountId);
        return new SaveStateResponse(true);
    }

    /**
     * "Stop saving it." {@code DELETE /v1/projects/{id}/save}.
     *
     * <p>200 with the state rather than 204, unlike {@code DELETE /v1/projects/{id}/remind}
     * beside it. The difference is that a withdrawal from a mailing list must not report
     * whether it did anything — {@code PrelaunchService} explains why — while this endpoint is
     * authenticated and the caller is being told about their own list, which they may read in
     * full anyway.
     */
    @DeleteMapping("/v1/projects/{projectId}/save")
    public SaveStateResponse unsave(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {
        UUID accountId = callerOf(accessToken);
        limit(accountId);
        signals.unsave(projectId, accountId);
        return new SaveStateResponse(false);
    }

    /** "Follow this account." C-10, addressed by the slug §10.2's profile route uses. */
    @PostMapping("/v1/users/{slug}/follow")
    public FollowStateResponse follow(@AuthenticationPrincipal Jwt accessToken, @PathVariable String slug) {
        UUID accountId = callerOf(accessToken);
        limit(accountId);
        signals.follow(slug, accountId);
        return new FollowStateResponse(true);
    }

    /** "Stop following." */
    @DeleteMapping("/v1/users/{slug}/follow")
    public FollowStateResponse unfollow(@AuthenticationPrincipal Jwt accessToken, @PathVariable String slug) {
        UUID accountId = callerOf(accessToken);
        limit(accountId);
        signals.unfollow(slug, accountId);
        return new FollowStateResponse(false);
    }

    /**
     * The caller's saved campaigns — §10.2's {@code GET /v1/me/saved}.
     *
     * <p>Not rate limited and not cached. It is a read of one account's own rows behind a
     * bearer token, and a {@code Cache-Control} header on somebody's private list is how a
     * shared proxy comes to hold it.
     *
     * @param cursor the opaque value the previous page returned, or absent for the first page
     * @param size how many rows, clamped to the configured ceiling rather than refused
     */
    @GetMapping("/v1/me/saved")
    public SavedListResponse saved(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) Integer size) {

        return SavedListResponse.of(signals.saved(callerOf(accessToken), SignalCursor.decode(cursor), size));
    }

    /**
     * The accounts the caller follows.
     *
     * <p><strong>Not in §10.2's list, and added with the rest of C-10 rather than left
     * out.</strong> The specification names {@code GET /v1/me/saved} and no counterpart for
     * following, which is an omission rather than a decision: a client that can follow and
     * unfollow but cannot ask what it is following has to remember, and the answer would be
     * wrong on a second device. §10.2 is updated to carry it.
     */
    @GetMapping("/v1/me/following")
    public FollowingListResponse following(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) Integer size) {

        return FollowingListResponse.of(signals.following(callerOf(accessToken), SignalCursor.decode(cursor), size));
    }

    /**
     * One budget across all four writes.
     *
     * <p>Together rather than one each, for {@code CommentController}'s reason: separate
     * counters would let a script that had spent its saves carry on spending follows, which is
     * the same enumeration arriving through the other endpoint.
     */
    private void limit(UUID accountId) {
        CommunityProperties.Signals limits = properties.signals();
        RateLimits.enforce(
                rateLimiter.recordAttempt("signal:account:" + accountId, limits.writesPerAccount(), limits.window()));
    }

    /**
     * The account making the request.
     *
     * <p>From our own signature and never from a path or a body parameter, as everywhere else:
     * an endpoint that took the account from the request would let anybody save a campaign on
     * somebody else's behalf.
     */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
