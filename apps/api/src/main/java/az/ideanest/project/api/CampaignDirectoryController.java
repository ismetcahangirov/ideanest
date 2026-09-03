package az.ideanest.project.api;

import az.ideanest.project.application.CampaignDirectory;
import az.ideanest.project.application.PublicProjectPage;
import az.ideanest.project.application.Taxonomy;
import az.ideanest.project.domain.ProjectState;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

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

    /** For the story column, which is {@code jsonb} held as text. See {@link StoryJson}. */
    private final ObjectMapper json;

    public CampaignDirectoryController(CampaignDirectory directory, ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    /**
     * One page, newest first.
     *
     * @param state narrows to one state. Absent means every campaign, which is the
     *     default because the question this screen exists to answer is "what is there"
     *     — any value of §6.1's enum is a legitimate filter, unlike the submission
     *     queue, and a value outside it is a 400 from the binder
     * @param creatorId narrows to one person's campaigns — #404. What the console's account
     *     detail screen reads, and it combines with the two filters beside it rather than
     *     replacing them: "this creator's suspended campaigns" is a question a moderator
     *     asks with the person's row already open
     * @param query a search over the title, the campaign's path, the creator's name and
     *     path, or an identifier — #404. This screen is the only one that lists campaigns in
     *     every state and it had no input of any kind. Blank is no search rather than a
     *     search for nothing, so a cleared form behaves like a fresh one
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
            @RequestParam(required = false) UUID creatorId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {

        return CampaignDirectoryResponse.of(directory.page(
                staffOf(accessToken), state, creatorId, query, after, directory.pageSize(limit)));
    }

    /**
     * One campaign as its page reads, in any state — #399's staff preview.
     *
     * <p><strong>The endpoint the submission queue was missing.</strong> That queue asks
     * for an irreversible decision about a campaign and linked to the public page, which
     * for a campaign in review is a 404 by construction — so approval happened on a title
     * and a goal figure. This serves the same projection the public page is served from,
     * without the state filter that makes the public one public.
     *
     * <p><strong>Never cached, unlike the endpoint it mirrors.</strong>
     * {@link PublicProjectController} sets a public {@code max-age} because a campaign
     * page is the same document for everybody; this one is a draft somebody is still
     * writing, read by a member of staff on the strength of a capability, and a copy of it
     * in a shared cache is a copy nothing revokes. Every other console read sets the same
     * header for the same reason.
     *
     * @param acceptLanguage §10.3's localisation header, narrowed by
     *     {@code Taxonomy.localeFor} exactly as on the public page — a moderator reading
     *     the console in Azerbaijani should not be shown an English category name
     * @return 404 when there is no such campaign; 403 when the caller is signed in and is
     *     not staff, which is {@code CampaignDirectory}'s check rather than this method's
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectPageResponse> preview(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @PathVariable UUID id) {

        PublicProjectPage page = directory.preview(staffOf(accessToken), id, Taxonomy.localeFor(acceptLanguage));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ProjectPageResponse.of(page, StoryJson.of(json, page.id(), page.story())));
    }

    private static UUID staffOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
