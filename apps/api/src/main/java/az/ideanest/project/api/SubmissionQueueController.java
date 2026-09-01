package az.ideanest.project.api;

import az.ideanest.project.application.CampaignSubmissionQueue;
import az.ideanest.project.domain.ProjectState;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the three moderation outcomes apply to.
 *
 * <p>Under {@code /v1/admin/moderation/submissions}, beside the outcomes
 * {@code ProjectModerationController} serves at {@code /v1/admin/moderation/{id}/…} and
 * the report queue at {@code /v1/admin/moderation/reports}. Those two arrived first and
 * between them left a hole: there were three ways to decide a campaign and no way to
 * find one. A moderator could reach a campaign only through a report somebody had filed
 * about it, so a submission nobody complained about waited indefinitely while its
 * creator was told it was under review.
 *
 * <p><strong>{@code GET} only.</strong> Deciding is still the three endpoints next
 * door, unchanged. A queue that also carried the decisions would be a second path into
 * a state machine whose single path is the reason the transition service exists.
 *
 * <p>{@code SecurityConfiguration}'s {@code /v1/admin/**} matcher requires an active
 * account and nothing more, so the real check is one layer in — see
 * {@code CampaignSubmissionQueue}, which asks for {@code MODERATE_CONTENT}.
 */
@RestController
@RequestMapping("/v1/admin/moderation/submissions")
public class SubmissionQueueController {

    private final CampaignSubmissionQueue queue;

    public SubmissionQueueController(CampaignSubmissionQueue queue) {
        this.queue = queue;
    }

    /**
     * One page, oldest first.
     *
     * @param state defaults to {@code SUBMITTED}, because that is the queue; the other
     *     three are how a decision is looked up afterwards. A value outside the four is
     *     refused rather than answered empty — see {@code UnreviewableStateException} —
     *     and a value outside the enum is a 400 from the binder
     * @param after the {@code nextCursor} of the previous page, or absent for the
     *     first. Keyset rather than an offset, for the reason the report queue gives:
     *     a moderator working the queue removes rows from it as they go, and an offset
     *     against a shifting set skips the campaigns that most need reading
     * @param limit clamped to {@code ideanest.project.submissions.max-page-size} rather
     *     than refused
     */
    @GetMapping
    public SubmissionQueueResponse submissions(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(defaultValue = "SUBMITTED") ProjectState state,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {

        return SubmissionQueueResponse.of(
                queue.page(moderatorOf(accessToken), state, after, queue.pageSize(limit)));
    }

    private static UUID moderatorOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
