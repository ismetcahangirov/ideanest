package az.ideanest.community.api;

import az.ideanest.community.application.ProjectUpdateService;
import az.ideanest.community.application.UpdateTimeline;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The Updates tab. §10.2's {@code GET /v1/projects/{id}/updates}, listed under
 * "Project — public".
 *
 * <p><strong>Public, and still identity-aware.</strong> The filter chain permits this
 * path without a token, because a visitor deciding whether to back a campaign is exactly
 * the audience an update list exists for. When a token <em>is</em> presented the
 * resource server still validates it, so {@code accessToken} is present for a signed-in
 * caller and null otherwise — and that is what lets the campaign's own team see the
 * updates they have scheduled for later from the same endpoint.
 *
 * <h2>Caching</h2>
 *
 * <p>§10.3 asks for {@code ETag} and {@code Cache-Control} on a public read. Both are
 * here, and the policy depends on who asked:
 *
 * <ul>
 *   <li><strong>{@code public, no-cache}</strong> for a visitor. "Keep this body and ask
 *       before you use it again" — the list changes rarely, so almost every revalidation
 *       is a 304, and the one time it is not is the moment a creator published something
 *       their backers are waiting for. A {@code max-age} would buy throughput by making
 *       exactly that request the stale one.
 *   <li><strong>{@code private, no-cache}</strong> for the campaign's team, whose body
 *       carries backers-only updates and ones nobody has been shown yet. The two
 *       audiences share a URL, so the response has to say for itself that it is not
 *       shareable; a shared cache handed the team's body would serve an unpublished
 *       announcement to the next visitor.
 * </ul>
 *
 * <p>The policy is written on the raw response rather than on the {@code ResponseEntity},
 * because {@code checkNotModified} writes the 304 itself and never passes through one —
 * and a 304 that dropped the policy would leave a cache deciding for itself how long the
 * stored body stays fresh.
 */
@RestController
public class PublicProjectUpdateController {

    private final ProjectUpdateService updates;

    public PublicProjectUpdateController(ProjectUpdateService updates) {
        this.updates = updates;
    }

    /**
     * One page of the campaign's updates, newest first.
     *
     * @param cursor the {@code nextCursor} from the previous page, or absent for the
     *     first. Cursor based per §10.3; see {@link ProjectUpdateListResponse} for why
     *     it is not an offset
     * @param limit how many to return, capped by the service. Absent means the default
     * @return {@code 404} for a campaign that does not exist and for one whose state is
     *     not public, identically, unless the caller is on its team; {@code 304} when the
     *     caller already holds this exact page
     */
    @GetMapping("/v1/projects/{projectId}/updates")
    public ResponseEntity<ProjectUpdateListResponse> list(
            WebRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @RequestParam(name = "cursor", required = false) Integer cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        UpdateTimeline timeline = updates.timeline(projectId, callerOf(accessToken), cursor, limit);
        ProjectUpdateListResponse body = ProjectUpdateListResponse.of(timeline);

        CacheControl policy = timeline.includesScheduled()
                ? CacheControl.noCache().cachePrivate()
                : CacheControl.noCache().cachePublic();
        response.setHeader(HttpHeaders.CACHE_CONTROL, policy.getHeaderValue());

        String etag = body.etag();
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    /**
     * The account making the request, or null when nobody signed in.
     *
     * <p>Taken from our own signature and never from a parameter: a viewer identifier a
     * client could choose would let anybody read another campaign's unpublished
     * announcements by naming its creator.
     */
    private static UUID callerOf(Jwt accessToken) {
        return accessToken == null ? null : UUID.fromString(accessToken.getSubject());
    }
}
