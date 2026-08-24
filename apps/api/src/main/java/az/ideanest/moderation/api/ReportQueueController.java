package az.ideanest.moderation.api;

import az.ideanest.moderation.ModerationProperties;
import az.ideanest.moderation.application.ReportModerationService;
import az.ideanest.moderation.domain.ReportState;
import az.ideanest.moderation.domain.ReportTargetType;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-02's report queue: what is waiting, and the two things staff can do about it.
 *
 * <p>Under {@code /v1/admin/moderation/reports}, beside the three campaign outcomes
 * {@code ProjectModerationController} already serves at
 * {@code /v1/admin/moderation/{id}/…}. §10.2 reserves the prefix for administration
 * and does not name these four routes; AD-02 names the capability, and a queue with
 * no way to read it or clear it is a table.
 *
 * <p><strong>{@code SecurityConfiguration} is not touched, and cannot do this
 * job.</strong> Its {@code /v1/admin/**} matcher requires an active account and
 * nothing more — there is no role model in the schema or the access token until epic
 * #100 — so every method here refuses through
 * {@code ReportModerationService}, one layer in, exactly as
 * {@code ProjectAccess.requireModeratable} does for campaigns. The check is in the
 * service rather than on the controller because a report resolution has to be audited
 * in the same transaction that performs it, and the transaction is the service's.
 *
 * <p><strong>Deciding a report does not act on what was reported.</strong> Neither
 * endpoint suspends a campaign or bans an account; both record a judgement about the
 * complaint. See {@code ReportModerationService} for why those are separate
 * privileged actions.
 */
@RestController
@RequestMapping("/v1/admin/moderation/reports")
public class ReportQueueController {

    private final ReportModerationService moderation;
    private final ModerationProperties properties;

    public ReportQueueController(ReportModerationService moderation, ModerationProperties properties) {
        this.moderation = moderation;
        this.properties = properties;
    }

    /**
     * The queue, one state at a time, oldest first.
     *
     * @param state defaults to {@code OPEN}, because that is the queue; the other two
     *     are how a decision is looked up afterwards. An unknown value is a 400 from
     *     the binder rather than an empty page, which would read as "nothing to do"
     * @param target narrows to one kind of reported thing, and is absent by default —
     *     the queue #101 serves is every kind, and adding this parameter does not
     *     change it. §4.11's AD-09 splits the same table into screens per kind, and the
     *     narrowing has to happen in the query: a client that filtered a page of
     *     twenty-five down to the two profile reports in it would have no way to ask
     *     for the other twenty-three, because the cursor it holds has moved past them.
     *     {@code PROJECT_UPDATE} is accepted and returns nothing, which is the honest
     *     answer while §10.2 gives an update no report route — see {@code ReportTargetType}
     * @param after the {@code nextCursor} of the previous page, or absent for the
     *     first. Keyset rather than an offset — a moderator working the queue removes
     *     rows from it as they go, and an offset against a shifting set skips reports
     * @param limit clamped to {@code ideanest.moderation.queue.max-page-size} rather
     *     than refused. A client asking for a thousand is asking for as much as it
     *     can have, and a 400 there would only teach it to ask for the maximum
     */
    @GetMapping
    public ReportQueueResponse queue(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(defaultValue = "OPEN") ReportState state,
            @RequestParam(required = false) ReportTargetType target,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {

        return ReportQueueResponse.of(
                moderation.queue(moderatorOf(accessToken), state, target, after, pageSize(limit)));
    }

    /** One report, for a client refreshing a row rather than a page. */
    @GetMapping("/{id}")
    public QueuedReportResponse report(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        return QueuedReportResponse.of(moderation.report(id, moderatorOf(accessToken)));
    }

    /**
     * "This complaint was justified." Terminal, and audited.
     *
     * <p>What it does not do is as important as what it does: the campaign is not
     * suspended and the account is not banned. Those are separate decisions with
     * separate consequences, and a moderator who agreed with a report should be able
     * to say so without also taking somebody's funding down.
     */
    @PostMapping("/{id}/uphold")
    public QueuedReportResponse uphold(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ReportResolutionRequest request) {

        return resolve(id, accessToken, ReportState.UPHELD, request);
    }

    /** "This complaint was not justified." Terminal, and audited for the same reason. */
    @PostMapping("/{id}/dismiss")
    public QueuedReportResponse dismiss(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ReportResolutionRequest request) {

        return resolve(id, accessToken, ReportState.DISMISSED, request);
    }

    private QueuedReportResponse resolve(
            UUID reportId, Jwt accessToken, ReportState outcome, ReportResolutionRequest request) {

        return QueuedReportResponse.of(
                moderation.resolve(reportId, moderatorOf(accessToken), outcome, noteOf(request)));
    }

    /** See {@link #queue} on why an over-large request is clamped rather than refused. */
    private int pageSize(Integer requested) {
        ModerationProperties.Queue limits = properties.queue();
        if (requested == null) {
            return limits.defaultPageSize();
        }
        return Math.clamp(requested.intValue(), 1, limits.maxPageSize());
    }

    private static String noteOf(ReportResolutionRequest request) {
        return request == null ? null : request.note();
    }

    /**
     * Whoever is signed in.
     *
     * <p>Checked against the configured moderator list by the service before anything
     * happens, and recorded as the {@code MODERATOR} on the audit row. Read from the
     * token's subject rather than from the request, for {@code ModeratorDirectory}'s
     * reason: an actor who could name themselves would be writing the record as well
     * as taking the decision.
     */
    private static UUID moderatorOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
