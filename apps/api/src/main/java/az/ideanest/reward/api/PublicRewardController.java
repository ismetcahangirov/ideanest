package az.ideanest.reward.api;

import az.ideanest.reward.application.PublicRewardCatalogue;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The reward list a backer sees. §4.5's first call, and PL-01.
 *
 * <p><strong>A separate controller because the audience is different</strong>, which
 * is the same split {@code PrelaunchController} makes and for the same reason. Every
 * endpoint on {@link RewardController} requires a bearer token and answers a stranger
 * 404, because it is the creator's editor; this one requires nothing, because a
 * visitor deciding whether to back a campaign is by definition somebody who may not
 * have registered. Put on the creator's controller it would inherit that controller's
 * projection and its exception advice, and the next reader would have to open the
 * filter chain to find out which endpoint is which.
 *
 * <p><strong>{@code /rewards/public} rather than the same path.</strong>
 * {@code GET /v1/projects/{id}/rewards} is taken, and it is the creator's list — its
 * javadoc says it returns secret tiers deliberately, "because a creator who cannot see
 * a secret tier in their own editor cannot edit or withdraw it". This endpoint is the
 * other half of that sentence: what makes a tier secret is that the public list leaves
 * it out. Serving both from one path on the strength of whether a token was presented
 * would mean one URL whose body depends on who is asking, which is a URL no cache can
 * be told the truth about.
 *
 * <h2>The secret token arrives as a query parameter</h2>
 *
 * <p>PL-15 calls a secret tier "reachable only by a private URL", so the token has to
 * be in a URL a creator can paste into an email — which rules out a header and a body,
 * neither of which survives being shared as a link. {@code ?token=} is the same
 * spelling {@code PrelaunchController} already uses for the unsubscribe token, and it
 * is repeatable, so a backer who holds two links can present both.
 *
 * <p>The cost is honest and worth stating: a query parameter appears in browser
 * history, in a {@code Referer}, and in whatever logs the reverse proxy keeps. That is
 * acceptable here and would not be for a credential — the token is a capability the
 * creator hands out by hand rather than something we authenticate anybody with (§7.2
 * on why it is stored in the clear), and the harm from a leaked one is the same harm
 * as the creator forwarding the link, which is what they do with it anyway.
 *
 * <h2>Caching</h2>
 *
 * <p>§10.3 asks for {@code ETag} and {@code Cache-Control} on a public read. Both are
 * here, and the freshness policy is deliberately the opposite of the discovery feed's:
 * <strong>{@code private, no-cache}</strong>, which means "keep this body, and ask
 * before you use it again".
 *
 * <ul>
 *   <li><strong>Not {@code max-age}.</strong> {@code /v1/discover} can be a minute
 *       stale because a card showing last minute's pledged total misleads nobody. This
 *       body carries {@code remainingQuantity}, and a reward list showing three places
 *       left when there are none is exactly the failure PL-01's live stock check
 *       exists to prevent: the backer picks a tier that is gone and finds out at the
 *       draft, after they decided. Any shared {@code max-age} at all buys throughput by
 *       spending the one guarantee this endpoint makes.
 *   <li><strong>Not {@code no-store}.</strong> Refusing to store the body would throw
 *       away the conditional request as well, and a revalidation that can answer 304
 *       is most of the saving with none of the staleness — a campaign page reloaded
 *       during a launch is the same list until somebody pledges.
 *   <li><strong>{@code private}</strong>, because a response carrying an unlocked
 *       secret tier is one only the holder of that link should be handed. The token is
 *       in the query string and therefore in the cache key of any correct shared
 *       cache, so this is belt as well as braces — but the belt is one word, and the
 *       failure it guards against is a private tier served to a stranger.
 * </ul>
 *
 * <p>The tag covers every field, stock included; see
 * {@link PublicRewardListResponse#etag()}. The trade-off left is that a client which
 * holds a fresh 304 still has to be told to re-read at the moment of pledging, and
 * that is what {@code POST /v1/pledges/draft} refusing with {@code REWARD_SOLD_OUT}
 * is for. No cache header can make a list of stock true at the moment it is read;
 * what it can do is refuse to make it older than it has to be.
 */
@RestController
public class PublicRewardController {

    private final PublicRewardCatalogue catalogue;

    public PublicRewardController(PublicRewardCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * The campaign's tiers and add-ons, as of now.
     *
     * @param tokens the secret tiers' tokens the caller is presenting, if any.
     *     Repeatable — {@code ?token=a&token=b}
     * @return {@code 404} for a campaign that does not exist and for one whose state
     *     is not public, identically; {@code 304} when the caller already holds this
     *     exact list
     */
    @GetMapping("/v1/projects/{projectId}/rewards/public")
    public ResponseEntity<PublicRewardListResponse> list(
            WebRequest request,
            HttpServletResponse response,
            @PathVariable UUID projectId,
            @RequestParam(name = "token", required = false) List<String> tokens) {

        PublicRewardListResponse body = PublicRewardListResponse.of(catalogue.of(projectId, tokens));

        // On the raw response rather than on the ResponseEntity, because the 304
        // below is written by checkNotModified and never passes through one — and a
        // 304 that dropped the policy would leave a cache deciding for itself how
        // long the stored body stays fresh, which is the one decision this endpoint
        // is not willing to delegate.
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().cachePrivate().getHeaderValue());

        String etag = body.etag();
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }
}
