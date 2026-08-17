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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The backer counts a campaign publishes. §4.4's header and Rewards tab.
 *
 * <h2>Counts, and nobody's name</h2>
 *
 * <p>§4.4 makes backer data public only in aggregate — the header's count, a count
 * beside each tier, and the Community tab's statistics — and never lists an
 * individual. Whether it should is #209, which carries {@code status: needs-decision},
 * and `CLAUDE.md` §5 is explicit that an endpoint is not the place to answer one of
 * those by default. So this serves the aggregates the specification asks for and
 * nothing else.
 *
 * <p>What #57 built alongside it is the guarantee for the day that decision lands:
 * {@link az.ideanest.pledge.application.PublicBacker} is a sealed pair in which the
 * anonymous variant has nowhere to put an identity. It has no consumer today, and its
 * own class comment says so rather than implying otherwise.
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
 * <p>No credential is taken and none would be used. Neither the backer nor the creator
 * sees anything here that a stranger does not, and with no names in the body there is
 * nothing either of them could be shown that the other should not: a count is a count.
 * A backer confirms their own choice took effect at {@code GET /v1/pledges/{id}},
 * which is theirs and already answers {@code isAnonymous}; the creator's list is the
 * dashboard endpoint above.
 *
 * <h2>Caching</h2>
 *
 * <p>{@code ETag} and {@code Cache-Control: public, max-age=60}, per §10.3 — the same
 * window {@code DiscoveryController} uses and the same argument, which now applies
 * without a caveat: this body is two integers and a list of integers, so there is no
 * personal data in it to hold in a shared cache and nothing a stale copy could reveal
 * about a person. What goes stale is a count, and a backer count a minute old misleads
 * nobody. That is exactly the distinction {@code PublicRewardController} draws when it
 * refuses any {@code max-age} at all: its body carries live stock, and a reward list
 * showing places that have gone is the failure PL-01 exists to prevent. Nothing here
 * is stock.
 *
 * <p><strong>An earlier revision of this endpoint served the backers themselves</strong>,
 * and defended the same window partly on the grounds that the worst a stale copy could
 * do was show a name a backer had just withdrawn. That risk is gone with the field, and
 * the window is kept on the argument above rather than on the one it replaced.
 *
 * <h2>No pagination, and nothing to paginate</h2>
 *
 * <p>There is no {@code ?limit=} and no cursor, because the response is a fixed-size
 * summary plus one row per reward tier — and §5.3 caps a campaign's tiers. If #209
 * decides a public list should exist, pagination is a question for whoever builds it,
 * along with the ordering a cursor would commit this API to.
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
     * The campaign's backer count, and the count beside each of its reward tiers.
     *
     * @return {@code 404} for a campaign that does not exist and for one whose state is
     *     not public, identically; {@code 304} when the caller already holds this exact
     *     body
     */
    @GetMapping("/v1/projects/{projectId}/backers/public")
    public ResponseEntity<PublicBackerListResponse> counts(
            WebRequest request, HttpServletResponse response, @PathVariable UUID projectId) {

        PublicBackerListResponse body = PublicBackerListResponse.of(backers.of(projectId));

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
