package az.ideanest.pledge.api;

import az.ideanest.pledge.application.PublicBackers;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The backers a campaign publishes. §4.5's PL-12, from the reading side.
 *
 * <h2>{@code /backers/public}, and why not {@code /backers}</h2>
 *
 * <p>§10.2 already lists {@code GET /v1/projects/{id}/backers}, under <strong>Dashboard
 * </strong>. That is the creator's, it is #97, and it must show every backer by name
 * including the anonymous ones — a creator who cannot see who to ship to cannot ship,
 * which is the sentence V17 puts beside the column itself. Two endpoints answering
 * "the backers of this campaign" with two different answers must not share a path:
 * one URL whose body depends on whether a token was presented is a URL no cache can be
 * told the truth about, and it is one review away from the creator's projection being
 * served to a stranger. {@code /rewards/public} made exactly this split against the
 * creator's {@code /rewards} and this follows it, spelling included.
 *
 * <h2>Public, because there is nobody to ask</h2>
 *
 * <p>No credential is taken and none would be used. The body does not vary by viewer,
 * deliberately, and that answers the two questions the reader is about to ask:
 *
 * <ul>
 *   <li><strong>A backer does not see their own anonymous pledge here.</strong> They
 *       see it at {@code GET /v1/pledges/{id}}, which is theirs, requires their token,
 *       and already answers {@code isAnonymous} so they can confirm the choice took
 *       effect. Making this endpoint reveal one row to one viewer would make it
 *       uncacheable for everybody and would make "is this list hiding me" depend on
 *       who loaded the page.
 *   <li><strong>Neither does the creator.</strong> Not because they may not know — they
 *       must — but because this is not where they find out. Their list is the dashboard
 *       endpoint above, which is authorised, not cached, and out of this issue's scope.
 * </ul>
 *
 * <h2>Caching</h2>
 *
 * <p>{@code ETag} and {@code Cache-Control}, per §10.3, and {@code public, max-age=60}
 * — the same window {@code DiscoveryController} uses, for the same reason and not by
 * copying. Nothing in this body belongs to a person who did not publish it, so a shared
 * cache may hold it; and a backer count a minute old misleads nobody, which is the
 * distinction {@code PublicRewardController} draws when it refuses any {@code max-age}
 * at all for a body carrying live stock. Nothing here is stock.
 *
 * <p>The one thing a stale copy could get wrong is a name a backer has just asked to
 * withdraw, and a minute is the bound on it. That bound is a decision rather than an
 * oversight: PL-09 lets a backer edit their pledge until the deadline, so the flag can
 * change, and if a minute is ever judged too long the answer is a shorter window here
 * rather than a client that has to guess.
 *
 * <h2>What this endpoint does not do</h2>
 *
 * <p><strong>It does not paginate.</strong> §10.3's pagination is cursor based, and a
 * cursor is a commitment to an ordering that clients then depend on. There is no client
 * yet — this is the first public per-backer surface the platform has had — so the
 * ordering to commit to is not known, and inventing one now would mean either keeping
 * it or breaking the first consumer. What exists instead is {@code ?limit=}, bounded by
 * {@link PublicBackers#MAX_PAGE_SIZE}, which is enough for §4.4's "recent backers" and
 * cannot ask an unbounded question of the pledge table. A campaign with more backers
 * than that has more backers than that: {@code backerCount} says so, and it is the
 * number the page renders.
 */
@RestController
public class PublicBackerController {

    /** See the class comment. Deliberately the same window the discovery feed uses. */
    static final long MAX_AGE_SECONDS = 60;

    private final PublicBackers backers;

    public PublicBackerController(PublicBackers backers) {
        this.backers = backers;
    }

    /**
     * The campaign's backer counts, and a page of the backers themselves.
     *
     * @param limit how many backers to return, {@code 1..100}. Clamped rather than
     *     refused — see {@link PublicBackers#of}
     * @return {@code 404} for a campaign that does not exist and for one whose state is
     *     not public, identically; {@code 304} when the caller already holds this exact
     *     body
     */
    @GetMapping("/v1/projects/{projectId}/backers/public")
    public ResponseEntity<PublicBackerListResponse> list(
            WebRequest request,
            HttpServletResponse response,
            @PathVariable UUID projectId,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {

        PublicBackerListResponse body = PublicBackerListResponse.of(backers.of(projectId, limit));

        // On the raw response rather than on the ResponseEntity, for the reason
        // PublicRewardController gives: the 304 below is written by checkNotModified
        // and never passes through one, and a revalidation that dropped the policy
        // would leave a cache holding this body under whatever freshness it inferred.
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.maxAge(MAX_AGE_SECONDS, TimeUnit.SECONDS)
                        .cachePublic()
                        .getHeaderValue());

        String etag = body.etag();
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }
}
