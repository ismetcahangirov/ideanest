package az.ideanest.fx;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the rates come from and how long one may be believed — issue #327.
 *
 * <p>Defaulted in code rather than in {@code application.yml}, for the reason every other
 * properties record in this service gives: a deployment that configures none of this has to
 * start, and the value it starts with has to be one somebody argued for rather than a zero
 * left behind by binding.
 *
 * @param enabled whether the platform offers a display currency at all. <strong>Off by
 *     default</strong>, and that is the one default here that is not merely conservative:
 *     turning it on means the service makes an outbound HTTP call to a third party on a
 *     timer, and a deployment that has not decided to do that must not start doing it
 *     because it upgraded. {@code IDEANEST_FX_ENABLED=true} is the decision
 * @param sourceUrl the document to fetch, with {@code {date}} where the source wants
 *     {@code dd.MM.yyyy}. Configuration rather than a constant so that a mirror, a proxy or
 *     a fixture can be pointed at in an environment that must not reach the internet
 * @param baseCurrency the currency the source prices everything in. {@code AZN}, because
 *     the source is Azerbaijan's central bank and §21.2 phase 1 collects in manat
 * @param displayCurrencies what a reader may choose. A closed set rather than "whatever the
 *     source published": the source lists forty currencies including gold, and a settings
 *     screen offering to price a campaign in troy ounces is a screen nobody designed
 * @param refreshSchedule §21.2's hourly cache, as a cron expression. {@code -} registers the
 *     job without scheduling it, which is what the test profile uses
 * @param maxAge how old a rate may be and still be shown. Beyond it the approximation
 *     disappears rather than ageing quietly — see the package note on degrading to absence
 * @param requestTimeout how long to wait for the source. Short, because nothing waits on
 *     this: the job runs on a timer and a missed pass costs an hour of freshness
 */
@ConfigurationProperties(prefix = "ideanest.fx")
public record FxProperties(
        boolean enabled,
        String sourceUrl,
        String baseCurrency,
        List<String> displayCurrencies,
        String refreshSchedule,
        Duration maxAge,
        Duration requestTimeout) {

    /**
     * The Central Bank of Azerbaijan's daily rates.
     *
     * <p>§21.2 says "central bank rates" and §22.1 names the Central Bank of Azerbaijan as
     * the regulator, so this is the source the specification means. The document is public,
     * needs no key, and is served per day at a path carrying the date.
     */
    public static final String DEFAULT_SOURCE_URL = "https://www.cbar.az/currencies/{date}.xml";

    private static final String DEFAULT_BASE_CURRENCY = "AZN";

    /**
     * The four §21.2's phase 2 names, minus the manat the platform already prices in.
     *
     * <p>Deliberately the same list. A reader who can be shown a campaign in dollars today
     * is the reader who will be able to <em>back</em> one in dollars when phase 2 lands, and
     * two lists that drift apart would mean a display currency nobody can ever pledge in.
     */
    private static final List<String> DEFAULT_DISPLAY_CURRENCIES = List.of("USD", "EUR", "TRY", "RUB");

    /**
     * Hourly, at five past.
     *
     * <p>§21.2 asks for an hourly cache and the source publishes daily, which are not in
     * conflict: the hour is how quickly the platform notices a new publication. Five past
     * rather than on the hour for the reason every schedule in §8.4 is offset — a fleet
     * whose jobs all fire at :00 is a fleet whose database sees one spike.
     */
    private static final String DEFAULT_REFRESH_SCHEDULE = "0 5 * * * *";

    /**
     * Four days.
     *
     * <p>Long enough to survive a long weekend — the source publishes on working days, so a
     * Friday rate is the newest one until Monday, and a New Year holiday stretches that
     * further. Short enough that a source which has genuinely stopped answering takes the
     * approximation off the screen within the week rather than showing a figure from
     * whenever the platform last succeeded.
     */
    private static final Duration DEFAULT_MAX_AGE = Duration.ofDays(4);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    public FxProperties {
        sourceUrl = blank(sourceUrl) ? DEFAULT_SOURCE_URL : sourceUrl.trim();
        baseCurrency = blank(baseCurrency) ? DEFAULT_BASE_CURRENCY : baseCurrency.trim().toUpperCase(java.util.Locale.ROOT);
        displayCurrencies = normalise(displayCurrencies);
        refreshSchedule = blank(refreshSchedule) ? DEFAULT_REFRESH_SCHEDULE : refreshSchedule.trim();
        maxAge = maxAge == null ? DEFAULT_MAX_AGE : maxAge;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;

        if (!sourceUrl.contains("{date}")) {
            // Fail at start-up rather than fetching the same document every hour for ever
            // and never noticing that the rates stopped moving.
            throw new IllegalArgumentException("The rate source URL needs a {date} placeholder: " + sourceUrl);
        }
        if (!maxAge.isPositive()) {
            throw new IllegalArgumentException("A rate is believable for some length of time");
        }
        if (!requestTimeout.isPositive()) {
            throw new IllegalArgumentException("A request waits for some length of time");
        }
        if (displayCurrencies.contains(baseCurrency)) {
            // A currency priced in itself is 1 by definition. Offering it as a display
            // choice would put "≈ ₼50" beside "₼50", which reads as a conversion that went
            // wrong rather than as one that was not needed.
            throw new IllegalArgumentException(
                    "The base currency is not a display choice; it is what a display currency approximates");
        }
    }

    /** Whether this deployment can offer {@code currency} as a display choice. */
    public boolean offers(String currency) {
        return enabled && displayCurrencies.contains(currency);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Upper-cased, de-duplicated, order preserved.
     *
     * <p>Order matters because it is the order of the options on a settings screen, and a
     * `Set` that reordered them would make the list depend on hash codes.
     */
    private static List<String> normalise(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_DISPLAY_CURRENCIES;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String currency : configured) {
            if (currency != null && !currency.isBlank()) {
                unique.add(currency.trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return unique.isEmpty() ? DEFAULT_DISPLAY_CURRENCIES : List.copyOf(unique);
    }
}
