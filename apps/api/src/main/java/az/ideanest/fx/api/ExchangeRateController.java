package az.ideanest.fx.api;

import az.ideanest.fx.application.ExchangeRates;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.2's display currency, for whoever is drawing a price — issue #327.
 *
 * <h2>Why the rates cross the wire rather than the converted amounts</h2>
 *
 * A campaign page shows a goal, a pledged total, and a price per reward tier; a discovery
 * feed shows twenty of them. Converting server-side would mean either an approximation
 * beside every money field in every response — trebling the size of a feed to carry a figure
 * most readers will not have asked for — or a query parameter that changes the shape of a
 * cached response, which is the same page under a second cache key per currency.
 *
 * <p>One rate is a number. The clients hold {@code @ideanest/money}, which already owns the
 * rounding rules, and they multiply once per figure they actually draw.
 *
 * <h2>Public and cacheable, unlike almost everything else</h2>
 *
 * There is nothing personal here — it is what a central bank published — so this is one of
 * the few reads on the platform a shared cache may hold. The lifetime is deliberately short
 * of the refresh interval so that a new publication is not hidden behind a cached copy of
 * the old one, and {@code stale-while-revalidate} means a reader never waits on the refresh.
 *
 * <h2>An empty list is a real answer</h2>
 *
 * A deployment with the feature off, one whose source has been unreachable past
 * {@code ideanest.fx.max-age}, and one that has simply not refreshed yet all answer
 * {@code {"base":"AZN","rates":[]}}. The clients read that as "no display currency is
 * offered" and draw nothing — which is the honest surface, and the one this endpoint exists
 * to make possible rather than leaving each client to guess at.
 */
@RestController
@RequestMapping("/v1/exchange-rates")
public class ExchangeRateController {

    /**
     * Ten minutes, over an hourly refresh.
     *
     * <p>Short enough that a new publication is visible within a sixth of the interval that
     * produced it; long enough that a discovery feed's worth of readers does not turn into a
     * read per page view. The stale window is a day because a rate that is ten minutes past
     * its cache lifetime is not wrong — it is the same number, and making somebody wait for
     * a round trip to confirm that is the worse trade.
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private static final Duration STALE_WHILE_REVALIDATE = Duration.ofDays(1);

    private final ExchangeRates rates;

    public ExchangeRateController(ExchangeRates rates) {
        this.rates = rates;
    }

    /** Every display currency this deployment can honestly offer right now. */
    @GetMapping
    public ResponseEntity<RatesResponse> rates() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic().staleWhileRevalidate(STALE_WHILE_REVALIDATE))
                .body(RatesResponse.of(rates));
    }

    /**
     * @param base the currency every rate below is expressed in. Sent rather than assumed,
     *     because a client that hard-coded {@code AZN} would silently convert from the wrong
     *     currency the day §21.2's phase 2 gives a campaign another one
     * @param rates one per currency a reader may choose, or empty. See the class note on why
     *     empty is an answer rather than a failure
     */
    public record RatesResponse(String base, List<Rate> rates) {

        static RatesResponse of(ExchangeRates rates) {
            return new RatesResponse(
                    rates.baseCurrency(), rates.available().stream().map(Rate::of).toList());
        }
    }

    /**
     * One currency, and what one unit of it is worth.
     *
     * @param currency the currency being priced
     * @param rate units of {@code base} per ONE unit of {@code currency}, as a
     *     <strong>string</strong>. §10.3 keeps money out of JSON numbers because an IEEE 754
     *     double cannot hold {@code 599.00} exactly; a rate is not money but it is the thing
     *     money is computed from, and 1.7000000000 parsed as a double and multiplied out is
     *     the same class of error one step earlier
     * @param publishedFor the day the source says it is in force from, so a screen can say
     *     "as of" rather than implying it is current to the minute
     * @param fetchedAt when the platform last saw it. Different from {@code publishedFor}: a
     *     source that answers every hour with last Tuesday's rates has a fresh fetch and a
     *     stale rate
     */
    public record Rate(String currency, String rate, LocalDate publishedFor, Instant fetchedAt) {

        static Rate of(ExchangeRates.Quote quote) {
            return new Rate(quote.currency(), plain(quote.rate()), quote.publishedFor(), quote.fetchedAt());
        }

        /**
         * {@code toPlainString}, never {@code toString}.
         *
         * <p>A {@code BigDecimal} whose scale and precision line up renders in scientific
         * notation — {@code 2.0484E-2} for the rouble — and a client parsing that with a
         * decimal library that does not accept exponents gets nothing rather than a rate.
         */
        private static String plain(BigDecimal rate) {
            return rate.toPlainString();
        }
    }
}
