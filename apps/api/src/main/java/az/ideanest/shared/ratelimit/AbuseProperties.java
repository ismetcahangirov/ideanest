package az.ideanest.shared.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The budgets for the public reads, which belong to no module's own settings.
 *
 * <p>Every other limit in §17.3 guards a module's own endpoint and is configured
 * with it — {@code ideanest.auth.rate-limit}, {@code ideanest.pledge.rate-limit}.
 * Discovery has no settings block of its own and needs none for anything else: a
 * search budget is not a search feature, it is the one control standing between an
 * unauthenticated endpoint and whoever wants to spend the database on it.
 *
 * @param searchesPerAddress §17.3's "search 60/min", spent by {@code GET /v1/search}
 *     and {@code GET /v1/discover} together
 * @param suggestionsPerAddress the same budget for autocomplete, counted separately
 *     and set higher
 * @param window one minute, which is the period §17.3 states the search number over
 */
@ConfigurationProperties(prefix = "ideanest.abuse")
public record AbuseProperties(int searchesPerAddress, int suggestionsPerAddress, Duration window) {

    /** §17.3: "search 60/min". */
    private static final int DEFAULT_SEARCHES_PER_ADDRESS = 60;

    /** See {@link #AbuseProperties} for why this is not the same number. */
    private static final int DEFAULT_SUGGESTIONS_PER_ADDRESS = 300;

    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /** §17.3's numbers, for a deployment that configures none of them. */
    public static AbuseProperties defaults() {
        return new AbuseProperties(DEFAULT_SEARCHES_PER_ADDRESS, DEFAULT_SUGGESTIONS_PER_ADDRESS, DEFAULT_WINDOW);
    }

    public AbuseProperties {
        // Binding leaves an omitted property at its zero value, and a limit of
        // zero is an endpoint nobody can call. §17.3's number is the fallback
        // rather than "unlimited", because failing open on a rate limit is a
        // configuration mistake with no symptom until it is exploited.
        searchesPerAddress = searchesPerAddress == 0 ? DEFAULT_SEARCHES_PER_ADDRESS : searchesPerAddress;
        suggestionsPerAddress =
                suggestionsPerAddress == 0 ? DEFAULT_SUGGESTIONS_PER_ADDRESS : suggestionsPerAddress;
        window = window == null ? DEFAULT_WINDOW : window;

        if (searchesPerAddress < 1 || suggestionsPerAddress < 1) {
            throw new IllegalArgumentException("A caller may make at least one public read a window");
        }
        if (!window.isPositive()) {
            throw new IllegalArgumentException("A rate-limit window is a positive duration");
        }
    }
}
