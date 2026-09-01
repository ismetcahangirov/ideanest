package az.ideanest.project.api;

import az.ideanest.project.application.CampaignDirectory;
import az.ideanest.project.domain.ProjectState;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What campaigns exist, for the console.
 *
 * <p>Under {@code /v1/admin/projects}, beside the suspension
 * {@code ProjectSuspensionController} serves at {@code /v1/admin/projects/{id}/suspend}.
 * That endpoint takes the id of a campaign a member of staff already has; this is where
 * they get one. Until it existed, the console could reach a campaign only through a
 * report filed about it or through the submission queue — both of which list campaigns
 * that have done something, and neither of which can answer "what is on the platform".
 *
 * <p><strong>{@code GET} only.</strong> Suspending is the endpoint next door and the
 * three moderation outcomes are under {@code /v1/admin/moderation}, unchanged. A
 * directory that also carried decisions would be a second path into a state machine
 * whose single path is the reason the transition service exists.
 *
 * <p>{@code SecurityConfiguration}'s {@code /v1/admin/**} matcher requires an active
 * account and nothing more, so the real check is one layer in — see
 * {@link CampaignDirectory}, which asks for {@code MODERATE_CONTENT}.
 */
@RestController
@RequestMapping("/v1/admin/projects")
public class CampaignDirectoryController {

    private final CampaignDirectory directory;

    public CampaignDirectoryController(CampaignDirectory directory) {
        this.directory = directory;
    }

    /**
     * One page, newest first.
     *
     * @param state narrows to one state. Absent means every campaign, which is the
     *     default because the question this screen exists to answer is "what is there"
     *     — any value of §6.1's enum is a legitimate filter, unlike the submission
     *     queue, and a value outside it is a 400 from the binder
     * @param after the {@code nextCursor} of the previous page, or absent for the first.
     *     Keyset rather than an offset: campaigns are created while somebody is reading,
     *     and an offset against a growing list shows a row twice
     * @param limit clamped to {@code ideanest.project.directory.max-page-size} rather
     *     than refused
     */
    @GetMapping
    public CampaignDirectoryResponse campaigns(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(required = false) ProjectState state,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {

        return CampaignDirectoryResponse.of(
                directory.page(staffOf(accessToken), state, after, directory.pageSize(limit)));
    }

    private static UUID staffOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
