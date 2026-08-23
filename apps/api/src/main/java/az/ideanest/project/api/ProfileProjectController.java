package az.ideanest.project.api;

import az.ideanest.project.application.ProfileCampaigns;
import az.ideanest.project.application.ProfileCursor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The campaigns a person has created, to anybody — §4.2's created tab (#282), and §10.2's
 * {@code GET /v1/users/{slug}/projects}.
 *
 * <p><strong>Under {@code /v1/users} although it is served from the project
 * module.</strong> The path names what a client is asking about — this person's campaigns —
 * and the module that answers is an implementation detail no URL should carry.
 * {@code BackerSignalController} already spans three prefixes for the same reason, and the
 * alternative here is worse than untidy: a {@code /v1/projects} path taking a creator slug
 * would sit beside {@code /v1/projects/{creatorSlug}/{projectSlug}} and would have to be
 * told apart from it by a security matcher that has no route table to consult.
 *
 * <p><strong>Its own controller rather than a method on {@link PublicProjectController}.
 * </strong> That one serves a single campaign by a pair of slugs and caches it for a
 * minute; this serves a paged list keyed on an account, its 404 comes from the profile
 * rather than from any campaign, and its caching policy is the opposite. Sharing a class
 * would mean sharing an exception advice and a cache decision between two endpoints that
 * agree on neither.
 *
 * <h2>Caching</h2>
 *
 * <p>{@code ETag} and {@code Cache-Control: public, no-cache} — the same pair, and the same
 * argument, as {@link az.ideanest.user.api.PublicProfileController}. P-07 can withdraw this
 * list, and a shared cache holding it under a {@code max-age} is a list that stays readable
 * after its owner turned it off. Almost every revalidation of a profile is a 304 with no
 * body, so the freshness is cheap to give up.
 */
@RestController
public class ProfileProjectController {

    private final ProfileCampaigns campaigns;

    public ProfileProjectController(ProfileCampaigns campaigns) {
        this.campaigns = campaigns;
    }

    /**
     * One page of this person's campaigns, newest first.
     *
     * <p>Only §6.1's nine public states, which is decided in {@link ProfileCampaigns} by
     * the same constant {@code PublicProjects} refuses a campaign page with. A creator's
     * drafts, their submissions and anything trust and safety has stopped are absent, and
     * they are absent identically — there is nothing in the response to say a row was
     * withheld, which is what stops this list being an oracle for what somebody is
     * preparing.
     *
     * @param slug the profile. 404 for one nobody holds, one §17.4 has anonymised, and one
     *     whose owner chose {@code PRIVATE} — identically; see
     *     {@code ProfileNotFoundException}. Never 403: this endpoint takes no credential,
     *     so a 403 would be an oracle any stranger could ask
     * @param cursor the {@code nextCursor} from the previous page, or absent for the first.
     *     Cursor based per §10.3; {@link ProfileCursor} says why it is not an offset
     * @param limit how many to return, clamped by the service. Absent means the default
     * @return {@code 304} when the caller already holds this exact page
     */
    @GetMapping("/v1/users/{slug}/projects")
    public ResponseEntity<ProfileProjectListResponse> created(
            WebRequest request,
            HttpServletResponse response,
            @PathVariable String slug,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        ProfileProjectListResponse body =
                ProfileProjectListResponse.of(campaigns.createdBy(slug, ProfileCursor.decode(cursor), limit));

        // On the raw response rather than on the ResponseEntity: the 304 below is written
        // by checkNotModified and never passes through one, and a revalidation that dropped
        // the policy would leave a cache deciding for itself how long to hold a list its
        // owner may have withdrawn in the meantime.
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noCache().cachePublic().getHeaderValue());

        String etag = body.etag();
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }
}
