package az.ideanest.community.api;

import az.ideanest.community.application.FaqList;
import az.ideanest.community.application.ProjectFaqService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The FAQ tab. §10.2's {@code GET /v1/projects/{id}/faqs}, listed under
 * "Project — public".
 *
 * <p><strong>Public, and still identity-aware.</strong> The filter chain permits this
 * path without a token, because a visitor deciding whether to back a campaign is exactly
 * the audience an FAQ exists for — §4.4 puts it on the page a stranger reads before
 * registering. When a token <em>is</em> presented the resource server still validates it,
 * so {@code accessToken} is present for a signed-in caller and null otherwise, and that
 * is what lets a campaign's own team read the FAQ of a campaign that has not launched.
 *
 * <h2>Not paged, deliberately</h2>
 *
 * <p>§10.2 gives this endpoint no cursor, and an FAQ list is tens of rows: fifty at most,
 * because {@code ProjectFaqService} refuses the fifty-first. That cap is what makes the
 * absence of a cursor a bounded response rather than a hope — the same argument
 * {@code CollectionController#index} makes about a curated list of lists, and the same
 * consequence: <strong>if fifty ever stops being enough, the answer is a cursor here and
 * not a larger cap</strong>, because the alternative — returning the first fifty and
 * saying nothing — is a silent truncation, and a creator whose answer is not on the page
 * has no way to tell that from having never written it.
 *
 * <h2>Caching</h2>
 *
 * <p>§10.3 asks for {@code ETag} and {@code Cache-Control} on a public read. Both are
 * here, and the policy depends on who asked:
 *
 * <ul>
 *   <li><strong>{@code public, no-cache}</strong> for a visitor. "Keep this body and ask
 *       before you use it again" — an FAQ changes rarely, so almost every revalidation is
 *       a 304, and the one time it is not is the moment a creator has just answered the
 *       question everybody is asking. A {@code max-age} would buy throughput by making
 *       exactly that request the stale one.
 *   <li><strong>{@code private, no-cache}</strong> for the campaign's team. The body is
 *       the same body a visitor would get — an FAQ entry has no visibility of its own —
 *       but the team can read this tab on a campaign that is <em>not publicly
 *       visible</em>, and a shared cache handed that body would serve an unlaunched
 *       campaign's FAQ to the next stranger who asked for it under the same URL.
 * </ul>
 *
 * <p>The policy is written on the raw response rather than on the {@code ResponseEntity},
 * because {@code checkNotModified} writes the 304 itself and never passes through one —
 * and a 304 that dropped the policy would leave a cache deciding for itself how long the
 * stored body stays fresh.
 */
@RestController
public class PublicProjectFaqController {

    private final ProjectFaqService faqs;

    public PublicProjectFaqController(ProjectFaqService faqs) {
        this.faqs = faqs;
    }

    /**
     * The campaign's FAQ entries, in the creator's order.
     *
     * @return {@code 404} for a campaign that does not exist and for one whose state is
     *     not public, identically, unless the caller is on its team; {@code 304} when the
     *     caller already holds this exact list
     */
    @GetMapping("/v1/projects/{projectId}/faqs")
    public ResponseEntity<ProjectFaqListResponse> list(
            WebRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId) {

        FaqList list = faqs.list(projectId, callerOf(accessToken));
        ProjectFaqListResponse body = ProjectFaqListResponse.of(list);

        CacheControl policy =
                list.forTeam() ? CacheControl.noCache().cachePrivate() : CacheControl.noCache().cachePublic();
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
     * client could choose would let anybody read an unlaunched campaign's FAQ by naming
     * its creator.
     */
    private static UUID callerOf(Jwt accessToken) {
        return accessToken == null ? null : UUID.fromString(accessToken.getSubject());
    }
}
