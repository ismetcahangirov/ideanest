package az.ideanest.project.api;

import az.ideanest.project.application.ProjectTransitionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three moderation outcomes for a submitted campaign.
 *
 * <p>In the {@code project} module rather than in {@code moderation} because all
 * three do exactly one thing: move this state machine. The moderation module owns
 * the queue, the case, the reports that feed it, and the reviewer's workflow, and
 * when it exists it will call these same transitions. Putting three endpoints there
 * first would mean a module whose only content is a call into another module's
 * service.
 *
 * <p><strong>There is no role check, and that is a gap this pull request names
 * rather than papers over.</strong> The service has no role model: nothing in the
 * schema, the access token, or {@code SecurityConfiguration} distinguishes platform
 * staff from anybody else, so what these endpoints require is what the filter chain
 * can enforce today — an authenticated caller whose account is in good standing.
 * Which means, plainly, that any signed-in user can approve any campaign. Epic #100
 * owns administrative roles; the check goes in {@code ProjectAccess.requireModeratable},
 * which exists and is called by all three methods here for that reason.
 *
 * <p>Inventing a stand-in — a configured list of email addresses, a claim a client
 * could send, "the creator may not approve their own" — would be worse. Each looks
 * like authorisation, none of them is, and every one of them would have to be found
 * and removed rather than simply filled in.
 */
@RestController
@RequestMapping("/v1/admin/moderation")
public class ProjectModerationController {

    private final ProjectTransitionService transitions;
    private final ProjectEditResponses responses;

    public ProjectModerationController(ProjectTransitionService transitions, ProjectEditResponses responses) {
        this.transitions = transitions;
        this.responses = responses;
    }

    /** Clears a campaign for launch. A note is optional commentary. */
    @PostMapping("/{id}/approve")
    public ProjectEdit approve(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ModerationDecisionRequest request) {

        return responses.of(transitions.approve(id, moderatorOf(accessToken), noteOf(request)));
    }

    /**
     * Refuses a campaign. Terminal — §5.4 is mostly a list of things that are not
     * fixable — and the note is required because the creator is shown it.
     */
    @PostMapping("/{id}/reject")
    public ProjectEdit reject(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody ModerationDecisionRequest request) {

        return responses.of(transitions.reject(id, moderatorOf(accessToken), noteOf(request)));
    }

    /**
     * Sends a campaign back to its creator with a note.
     *
     * <p>Not in §10.2's endpoint list, and required by this epic's definition of
     * done. Without it a moderator's only options are to approve a campaign with a
     * fixable summary or to reject it — and a rejection cannot be undone.
     */
    @PostMapping("/{id}/request-changes")
    public ProjectEdit requestChanges(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody ModerationDecisionRequest request) {

        return responses.of(transitions.requestChanges(id, moderatorOf(accessToken), noteOf(request)));
    }

    private static String noteOf(ModerationDecisionRequest request) {
        return request == null ? null : request.note();
    }

    /**
     * Whoever is signed in.
     *
     * <p>Recorded as the {@code MODERATOR} on the transition row. That the platform
     * cannot yet verify they are one is the gap above; recording the account that
     * acted is still worth doing, because it is what makes the decision reviewable
     * once there is a way to tell.
     */
    private static UUID moderatorOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
