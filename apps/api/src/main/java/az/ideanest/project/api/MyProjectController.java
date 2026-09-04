package az.ideanest.project.api;

import az.ideanest.project.application.ProfileCampaigns;
import az.ideanest.project.application.ProfileCursor;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own campaigns, drafts included — {@code GET /v1/me/projects}.
 *
 * <h2>Why this endpoint exists at all</h2>
 *
 * <p>It did not, and the gap was not visible from the API. {@code POST /v1/projects} creates
 * a draft and answers with its identifier, and every other project endpoint takes that
 * identifier in its path — so a creator who closed the tab held nothing. The web client had
 * one campaign-shaped control in its account menu and it was "start a campaign", which posts
 * a new draft, so the only route back to a half-finished one was a URL nobody had written
 * down. Reaching your own unfinished work should not require having bookmarked it.
 *
 * <h2>Not a variant of {@code /v1/users/{slug}/projects}</h2>
 *
 * <p>They differ in the two things that matter about a list: who may read it, and what it
 * contains. {@link ProfileProjectController} takes no credential, resolves a slug, and shows
 * §6.1's nine public states; this takes an access token, resolves nobody, and shows all
 * sixteen. Serving both from one path with a flag would put "am I looking at my own?" inside
 * a handler, which is the shape that eventually answers it wrongly.
 *
 * <p><strong>The account is the subject of the token and never a parameter.</strong> A slug
 * or an identifier in the query string would be an authorisation decision written as a
 * lookup, and this list carries drafts — the rows §6.1 publishes to nobody.
 *
 * <h2>Caching</h2>
 *
 * <p>{@code no-store, private}, matching {@code GET /v1/me/pledges} and for its reason
 * rather than by imitation. The body names work that has not been published, some of it
 * withheld by P-07 from the creator's own profile page; "revalidate before reuse" still
 * permits a shared proxy to hold a copy in the meantime, and there is no circumstance in
 * which one should. No {@code ETag} either: the saving a 304 buys is not worth a validator
 * for a body one person ever reads.
 */
@RestController
public class MyProjectController {

    private final ProfileCampaigns campaigns;

    public MyProjectController(ProfileCampaigns campaigns) {
        this.campaigns = campaigns;
    }

    /**
     * One page of the caller's campaigns, newest first.
     *
     * <p>Every state, so the draft somebody left and the submission they are waiting on are
     * both here — and so is a suspended campaign, which is the one its owner most needs to
     * see. The card carries {@code state}, so a client renders the difference rather than
     * inferring it.
     *
     * <p><strong>An empty list is a complete answer.</strong> A creator who has started
     * nothing gets {@code 200} with no rows, never a 404: there is no campaign missing, and
     * a client that had to tell an empty list apart from a failure would be the client that
     * prints "something went wrong" at somebody who has simply not started yet.
     *
     * @param cursor the {@code nextCursor} from the previous page, or absent for the first
     * @param limit how many to return, clamped by the service. Absent means the default
     */
    @GetMapping("/v1/me/projects")
    public ResponseEntity<ProfileProjectListResponse> mine(
            HttpServletResponse response,
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        UUID creatorId = UUID.fromString(accessToken.getSubject());
        ProfileProjectListResponse body =
                ProfileProjectListResponse.of(campaigns.mine(creatorId, ProfileCursor.decode(cursor), limit));

        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().cachePrivate().getHeaderValue());

        return ResponseEntity.ok(body);
    }
}
