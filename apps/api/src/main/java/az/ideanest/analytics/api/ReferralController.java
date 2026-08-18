package az.ideanest.analytics.api;

import az.ideanest.analytics.AnalyticsProperties;
import az.ideanest.analytics.application.CaptureReferralVisit;
import az.ideanest.analytics.application.ReferralReportService;
import az.ideanest.analytics.application.ReferralVisitService;
import az.ideanest.shared.ratelimit.ClientAddress;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Referral attribution over HTTP: recording a visit, and reading the report it feeds.
 *
 * <p><strong>Two endpoints with opposite audiences on one controller</strong>, unlike
 * the split {@code PrelaunchController} makes against {@code ProjectController}. The
 * reason the split is not repeated here is that there is nothing to keep apart: both
 * paths are this module's and neither shares a URL with the other's audience, so a
 * second class would only mean a second exception advice for the same two failures.
 * What does the keeping-apart is the paths themselves — {@code /referral-visits} takes
 * no credential and reveals nothing, {@code /referrers} requires one and reveals a
 * campaign's marketing — and {@code SecurityConfiguration} permits exactly the first.
 *
 * <p>Rate limiting is here rather than in the service, following
 * {@code PrelaunchController}: it is about the transport — a source address, a header,
 * a 429 with {@code Retry-After} — and a service should not have to know it is being
 * reached over HTTP.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}")
public class ReferralController {

    private final ReferralVisitService visits;
    private final ReferralReportService report;
    private final RateLimiter rateLimiter;
    private final AnalyticsProperties properties;

    public ReferralController(
            ReferralVisitService visits,
            ReferralReportService report,
            RateLimiter rateLimiter,
            AnalyticsProperties properties) {

        this.visits = visits;
        this.report = report;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * "Somebody arrived here, from there." §4.6's share links, at the moment they are
     * followed.
     *
     * <p><strong>Unauthenticated, because the visits that decide an attribution happen
     * before anybody signs in.</strong> An endpoint that required a token would only
     * ever see the last step of a journey and would report every campaign as
     * converting nobody except the people who arrived already logged in.
     *
     * <p><strong>And therefore rate limited per source address</strong>, which is the
     * one control standing between an open write and whoever wants to fill a creator's
     * report with invented sources. It is not fraud protection and does not pretend to
     * be: a determined attacker with many addresses can still put rows in this table,
     * which is exactly why the report folds everything past
     * {@code ideanest.analytics.referral.max-sources} into one line rather than
     * rendering whatever arrived.
     *
     * <p>An access token is read when the caller happens to have one, and that is the
     * whole of what makes the anonymous half of a journey count: presenting a token
     * this endpoint issued earlier, while signed in, attaches those earlier visits to
     * the account.
     *
     * @return 404 for a campaign that does not exist and for one that is not publicly
     *     visible, identically — this endpoint must not be a way to find out what other
     *     people are preparing
     */
    @PostMapping("/referral-visits")
    public ReferralVisitResponse visit(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody CaptureVisitRequest request,
            HttpServletRequest httpRequest) {

        AnalyticsProperties.Referral referral = properties.referral();
        RateLimits.enforce(rateLimiter.recordAttempt(
                "referral-visit:ip:" + ClientAddress.of(httpRequest),
                referral.visitsPerAddress(),
                referral.window()));

        return ReferralVisitResponse.of(visits.record(new CaptureReferralVisit(
                projectId, request.visitorToken(), request.toSource(), accountOf(accessToken))));
    }

    /**
     * {@code GET /v1/projects/{id}/referrers}: §10.2's dashboard endpoint, §4.7's
     * CD-03.
     *
     * <p>Who may read it is the project module's decision and is made one layer in,
     * asked for by name as {@code VIEW_FINANCES} through
     * {@code shared.access.ProjectAuthorisation}. A stranger gets a 404; a
     * collaborator holding a grant without that capability gets a 403.
     *
     * <p><strong>{@code Cache-Control: private, no-store}.</strong> Unlike the public
     * reads next door, this body is one campaign's marketing performance and belongs to
     * the account that asked for it: a shared cache holding it would be a shared cache
     * able to serve it to somebody else, and even a private one on a shared machine
     * would leave it on disk. §10.3 asks for caching on public reads and this is not
     * one.
     */
    @GetMapping("/referrers")
    public ResponseEntity<ReferrerReportResponse> referrers(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ReferrerReportResponse.of(report.of(projectId, accountOf(accessToken))));
    }

    /**
     * The account making the request, or null when nobody is signed in.
     *
     * <p>Null is reachable only on the capture endpoint, which is the one
     * {@code SecurityConfiguration} permits: everything else on this controller falls
     * through to the rule that requires a token. As everywhere else in the service, who
     * the caller is comes from our own signature and never from a path or a body.
     */
    private static UUID accountOf(Jwt accessToken) {
        return accessToken == null ? null : UUID.fromString(accessToken.getSubject());
    }
}
