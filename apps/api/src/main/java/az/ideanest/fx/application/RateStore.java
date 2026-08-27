package az.ideanest.fx.application;

import az.ideanest.fx.FxProperties;
import az.ideanest.fx.domain.ExchangeRate;
import az.ideanest.fx.infrastructure.ExchangeRateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes what a source published, and only what is new — issue #327.
 *
 * <p>A bean of its own rather than a method on {@link ExchangeRateRefreshJob}, for two
 * reasons that point the same way. The mechanical one: a {@code @Transactional} method
 * invoked from within its own class does not go through the proxy, so the annotation would
 * have had no effect. The design one: the fetch is an HTTP call to a third party and has no
 * business inside a database transaction, so the boundary belongs exactly here — after the
 * document has arrived and before anything is written.
 */
@Service
public class RateStore {

    private static final Logger log = LoggerFactory.getLogger(RateStore.class);

    private final ExchangeRateRepository rates;
    private final FxProperties properties;
    private final Clock clock;

    public RateStore(ExchangeRateRepository rates, FxProperties properties, Clock clock) {
        this.rates = rates;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Stores the publications not already held.
     *
     * <p>One transaction for the pass rather than one per rate. The forty rows of a
     * publication are one fact — "this is what the bank said today" — and a partial write
     * would leave a day in which four currencies converted and one did not, for no reason a
     * reader could see.
     *
     * <p>Existence is checked rather than the unique index being allowed to refuse. A
     * {@code DataIntegrityViolationException} would mark the transaction rollback-only and
     * take the rest of the batch with it, which on the eleven passes a day that find nothing
     * new would mean the whole pass failing as a matter of routine.
     *
     * @return how many were stored. Zero on almost every pass, because the source publishes
     *     daily and the refresh asks hourly
     */
    @Transactional
    public int store(az.ideanest.fx.domain.RateSource source, List<PublishedRate> published) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String base = properties.baseCurrency();
        List<ExchangeRate> fresh = new ArrayList<>();

        for (PublishedRate rate : published) {
            if (rate.quoteCurrency().equals(base)) {
                // A currency priced in itself is 1 by definition and V59 refuses the row.
                // The source does not publish one; this guards a mirror or a fixture that
                // does, where the failure would otherwise be a constraint violation naming
                // a column rather than the reason.
                continue;
            }
            boolean alreadyStored = rates.existsBySourceAndBaseCurrencyAndQuoteCurrencyAndPublishedFor(
                    source.name(), base, rate.quoteCurrency(), rate.publishedFor());
            if (alreadyStored) {
                continue;
            }
            fresh.add(ExchangeRate.published(
                    source, base, rate.quoteCurrency(), rate.rate(), rate.publishedFor(), now));
        }

        if (fresh.isEmpty()) {
            return 0;
        }

        rates.saveAll(fresh);
        // INFO, once per publication rather than once per currency: "the rates for the 27th
        // arrived at 09:05" is the line somebody looks for when a figure on a screen is not
        // the one they expected.
        log.info(
                "Stored {} exchange rate(s) priced in {}, published for {}.",
                fresh.size(),
                base,
                fresh.getFirst().getPublishedFor());
        return fresh.size();
    }
}
