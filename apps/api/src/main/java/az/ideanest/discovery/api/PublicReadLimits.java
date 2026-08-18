package az.ideanest.discovery.api;

import az.ideanest.shared.ratelimit.AbuseProperties;
import az.ideanest.shared.ratelimit.ClientAddress;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * §17.3's "search 60/min", applied to the endpoints that spend it.
 *
 * <p><strong>Per address, because there is nobody else to count.</strong> Every
 * other limit in §17.3 that guards a request carrying a token is counted per
 * account, for the reason {@code PledgeProperties} gives. These endpoints carry no
 * token at all — discovery is the first page a visitor sees — so the address is the
 * only handle there is, and the cost is the one a shared address always has: an
 * office or a mobile carrier behind one NAT shares a budget. That cost is why the
 * numbers are not tighter than §17.3's.
 *
 * <h2>One bucket for the feed and the search box, another for autocomplete</h2>
 *
 * <p>{@code /v1/discover}, {@code /v1/discover/facets} and {@code /v1/search} run
 * the same query against the same tables through the same service — the search
 * endpoint is an alias, see {@link SearchController} — so they share one budget. Two
 * budgets would mean a script alternating between them had twice the allowance for
 * exactly the work being bounded.
 *
 * <p><strong>Autocomplete is counted separately and higher.</strong> A suggestion is
 * one request per keystroke: a reader typing a ten-letter query spends ten of them,
 * and sixty a minute would refuse the sixth thing they searched for. It is also the
 * cheapest query here — a prefix match over titles, tags and taxa, cached for five
 * minutes at the edge — so the budget that bounds it is not the budget that bounds a
 * faceted feed scan.
 *
 * <h2>What this does not stop</h2>
 *
 * <p>The counter is in this replica's heap ({@code InMemoryRateLimiter}), so the
 * real ceiling is sixty a minute multiplied by the number of replicas, and a
 * distributed client is not bounded by it at all. Shared storage is #134's; what
 * this stops today is one script, which is what §17.3 lists it under.
 */
@Component
public class PublicReadLimits {

    private final RateLimiter rateLimiter;
    private final AbuseProperties properties;

    public PublicReadLimits(RateLimiter rateLimiter, AbuseProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** The feed, its facets, and the search box. */
    public void countRead(HttpServletRequest request) {
        RateLimits.enforce(rateLimiter.recordAttempt(
                "search:ip:" + ClientAddress.of(request), properties.searchesPerAddress(), properties.window()));
    }

    /** Autocomplete, on its own budget. */
    public void countSuggestion(HttpServletRequest request) {
        RateLimits.enforce(rateLimiter.recordAttempt(
                "suggest:ip:" + ClientAddress.of(request),
                properties.suggestionsPerAddress(),
                properties.window()));
    }
}
