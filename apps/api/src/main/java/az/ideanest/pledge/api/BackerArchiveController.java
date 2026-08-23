package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerArchive;
import az.ideanest.pledge.application.BackerCursor;
import az.ideanest.project.api.ProfileProjectListResponse;
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
 * What somebody has backed: the public archive, and the backer's own list — §4.2's P-04
 * (#274) and §4.8's pledge screens (#287).
 *
 * <p><strong>Two endpoints in one controller although one is public and the other is
 * not</strong>, which is the opposite of the split {@link PublicBackerController} makes
 * against the creator's dashboard — and the difference is what the two pairs share. Those
 * two answer "the backers of this campaign" with two different answers and must not share a
 * path, because one URL whose body depends on a token is a URL no cache can be told the
 * truth about. These two ask different questions of the same rows and already share
 * everything behind them: one service, one cursor, one advice, one page size. Splitting them
 * would put one feature in two files that a reader has to find both of.
 *
 * <p>They do <strong>not</strong> share a path, a response shape, or a caching policy, and
 * every one of those differences is a privacy rule stated in {@link BackerArchive}.
 *
 * <h2>The archive is a list of campaigns, not of pledges</h2>
 *
 * <p>{@code GET /v1/users/{slug}/backed} answers {@link ProfileProjectListResponse} — the
 * identical shape the created tab beside it answers, imported from the project module
 * rather than restated here. §4.2's P-04 makes the backed tab a list of campaigns and never
 * a list of amounts, and reusing the card is what makes that structural: there is nowhere in
 * the response for a number to go, so no later change can leak one by forgetting to filter.
 * A reader switching tabs also gets one grid rather than two that drifted apart.
 *
 * <h2>Caching</h2>
 *
 * <p>The archive carries {@code ETag} and {@code Cache-Control: public, no-cache}, the same
 * pair as the profile and its created tab and for the same reason: P-07 can withdraw this
 * list, and a shared cache holding it under a {@code max-age} is a list that stays readable
 * after its owner turned it off.
 *
 * <p>The backer's own list carries {@code private, no-store} and no validator. It is one
 * account's own rows behind a bearer token, it holds every amount they have committed, and
 * a {@code Cache-Control} that permitted storage is how a shared proxy comes to hold
 * somebody's spending. {@code BackerSignalController} makes the same call about
 * {@code GET /v1/me/saved} — this one has more to lose.
 */
@RestController
public class BackerArchiveController {

    private final BackerArchive archive;

    public BackerArchiveController(BackerArchive archive) {
        this.archive = archive;
    }

    /**
     * One page of the campaigns this person has publicly backed — §4.2's backed tab.
     *
     * <p>Anonymous pledges are absent, campaigns the public may not see are absent, and both
     * are absent identically: there is nothing in the response to say a row was withheld,
     * which is what stops the archive being an oracle for what somebody chose to hide.
     *
     * @param slug the profile. 404 for one nobody holds, one §17.4 has anonymised, and one
     *     whose owner chose {@code PRIVATE} — identically. Never 403: this endpoint takes no
     *     credential, so a 403 would be an oracle any stranger could ask
     * @param cursor the {@code nextCursor} from the previous page, or absent for the first
     * @param limit how many to return, clamped by the service. A page may come back shorter
     *     than this and still carry a cursor — {@link BackerArchive} says why
     * @return {@code 304} when the caller already holds this exact page
     */
    @GetMapping("/v1/users/{slug}/backed")
    public ResponseEntity<ProfileProjectListResponse> backed(
            WebRequest request,
            HttpServletResponse response,
            @PathVariable String slug,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        BackerArchive.BackedPage page = archive.backedBy(slug, BackerCursor.decode(cursor), limit);
        ProfileProjectListResponse body = ProfileProjectListResponse.of(
                page.campaigns(), page.next() == null ? null : page.next().encode());

        // On the raw response rather than on the ResponseEntity: the 304 below is written by
        // checkNotModified and never passes through one, and a revalidation that dropped the
        // policy would leave a cache deciding for itself how long to hold a list its owner
        // may have withdrawn in the meantime.
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noCache().cachePublic().getHeaderValue());

        String etag = body.etag();
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    /**
     * One page of the caller's own pledges, newest first — §4.8.
     *
     * <p>Every state, because the pledge somebody cancelled and the one whose card was
     * refused are what this screen exists to show, and the campaign in whatever state it is
     * in, because a suspended campaign is exactly when a backer needs to know which one it
     * was.
     *
     * <p>Not rate limited. It is a read of one account's own rows behind a bearer token, and
     * a budget on it would spend the same counter as a write nobody made.
     *
     * @param accessToken the caller. The account comes from our own signature and never from
     *     a path or a query parameter: an endpoint that took it from the request would serve
     *     anybody's pledges, with their amounts, to anybody holding any token
     */
    @GetMapping("/v1/me/pledges")
    public ResponseEntity<BackerPledgeListResponse> mine(
            HttpServletResponse response,
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        UUID backerId = UUID.fromString(accessToken.getSubject());
        BackerPledgeListResponse body =
                BackerPledgeListResponse.of(archive.pledgesOf(backerId, BackerCursor.decode(cursor), limit));

        // no-store rather than no-cache, and private rather than public. This body carries
        // every amount one person has committed on this platform; "revalidate before reuse"
        // still permits a shared proxy to hold it in the meantime, and there is no
        // circumstance in which it should.
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().cachePrivate().getHeaderValue());

        return ResponseEntity.ok(body);
    }
}
