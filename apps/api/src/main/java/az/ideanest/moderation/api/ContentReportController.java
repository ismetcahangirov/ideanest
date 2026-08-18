package az.ideanest.moderation.api;

import az.ideanest.moderation.ModerationProperties;
import az.ideanest.moderation.application.ReportingService;
import az.ideanest.moderation.application.SubmittedReport;
import az.ideanest.moderation.domain.ReportTargetType;
import az.ideanest.shared.ratelimit.ClientAddress;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * How somebody tells the platform that something is wrong. §4.9's C-06 and C-07, and
 * §10.2's {@code POST /v1/projects/{id}/report}.
 *
 * <p><strong>Two endpoints on one controller, because they are one feature and one
 * budget.</strong> Splitting them would mean two rate limiters to keep in step, and
 * somebody who had spent their reporting allowance on campaigns could then spend a
 * second one on people.
 *
 * <p><strong>Neither endpoint appears in {@code SecurityConfiguration}, and that is
 * the intended arrangement.</strong> Both fall through to the catch-all rule, so both
 * require a bearer token from an account that is not inside §17.4's deletion grace
 * period. Reporting is one of the few writes where requiring an account is not
 * friction but the mechanism: the duplicate suppression this feature is built on is
 * unstateable without an identity to compare, and V23's header has the other two
 * reasons.
 *
 * <p><strong>{@code POST /v1/comments/{id}/report} is deliberately absent.</strong>
 * §10.2 lists it and §4.9's community module has not been built — there is no
 * {@code comments} table, so an identifier cannot be checked and a moderator opening
 * the report would find nothing behind it. Accepting the report anyway would show the
 * reporter a success for a complaint nobody can ever read.
 * {@link ReportTargetType#COMMENT} is already in the taxonomy and in V23's
 * constraint, so publishing the route is a controller method rather than a migration.
 *
 * <p><strong>Rate limiting is here rather than in the service</strong>, following
 * {@code PrelaunchController} and {@code AuthController}: it is about the transport —
 * a source address, a 429, a {@code Retry-After} — and the service should not have to
 * know it is being reached over HTTP.
 */
@RestController
@RequestMapping("/v1")
public class ContentReportController {

    private final ReportingService reporting;
    private final RateLimiter rateLimiter;
    private final ModerationProperties properties;

    public ContentReportController(
            ReportingService reporting, RateLimiter rateLimiter, ModerationProperties properties) {
        this.reporting = reporting;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** "There is something wrong with this campaign." §4.9's C-06. */
    @PostMapping("/projects/{id}/report")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportResponse reportProject(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody ReportRequest request,
            HttpServletRequest httpRequest) {

        return report(ReportTargetType.PROJECT, id, accessToken, request, httpRequest);
    }

    /**
     * "There is something wrong with this person." AD-09's "profiles", and what a
     * ban is decided from.
     *
     * <p>Not in §10.2's endpoint list, and required by this issue's definition of
     * done — "report projects, comments, and users". Without it the only way to
     * report an account is to report one of its campaigns, which is the wrong object
     * for a complaint about impersonation or harassment and produces a queue where
     * every report about a person is filed against something they made.
     */
    @PostMapping("/users/{id}/report")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportResponse reportUser(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody ReportRequest request,
            HttpServletRequest httpRequest) {

        return report(ReportTargetType.USER, id, accessToken, request, httpRequest);
    }

    /**
     * The one implementation, and the one place the two budgets are spent.
     *
     * <p>Both limits are counted before anything is written, and the reporter's own
     * budget is counted first: it is the tighter of the two and the one an attacker
     * actually has to spend, so a client that is over it should not also have a unit
     * of the address budget taken from whoever shares its NAT.
     *
     * <p>202 rather than 201. The platform has the complaint and has created nothing
     * the client can go and read — the report is not addressable by the person who
     * made it, on purpose — and "accepted, a person will look at this" is what
     * actually happened.
     */
    private ReportResponse report(
            ReportTargetType targetType,
            UUID targetId,
            Jwt accessToken,
            ReportRequest request,
            HttpServletRequest httpRequest) {

        ModerationProperties.Reports limits = properties.reports();
        UUID reporterId = UUID.fromString(accessToken.getSubject());

        RateLimits.enforce(
                rateLimiter.recordAttempt("report:account:" + reporterId, limits.perReporter(), limits.window()));
        RateLimits.enforce(rateLimiter.recordAttempt(
                "report:ip:" + ClientAddress.of(httpRequest), limits.perClient(), limits.window()));

        SubmittedReport report =
                reporting.report(targetType, targetId, reporterId, request.reason(), request.detail());
        return ReportResponse.of(report);
    }
}
