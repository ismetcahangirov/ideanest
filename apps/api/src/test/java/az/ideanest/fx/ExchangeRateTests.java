package az.ideanest.fx;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.fx.application.Approximation;
import az.ideanest.fx.application.ExchangeRateRefreshJob;
import az.ideanest.fx.application.ExchangeRates;
import az.ideanest.fx.infrastructure.ExchangeRateRepository;
import az.ideanest.shared.money.Money;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.ScriptedRateSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §21.2's display currency: the rate table, the refresh, and the arithmetic — issue #327.
 *
 * <p>The assertions that carry the design, in the order they would hurt:
 *
 * <ul>
 *   <li>{@link #anAmountIsDividedByItsRate()} — <strong>the direction</strong>. It is
 *       invisible on a rate near one and a factor-of-thirty error on the lira, and it is the
 *       one mistake this whole feature can make. Asserted against a figure computed by hand.
 *   <li>{@link #aRateIsNotRoundedLikeMoney()} — a rate taken to two decimal places turns the
 *       lira's 0.0354 into 0.04, which is thirteen per cent of somebody's pledge.
 *   <li>{@link #aStaleRateIsNotShownAtAll()} and {@link #anUnreachableSourceKeepsWhatItHad()}
 *       — §21.2 degrades to absence, never to a guess.
 *   <li>{@link #refreshingTwiceInADayWritesOnce()} — the hourly cache over a daily source,
 *       which is what makes eleven of the twelve passes free.
 * </ul>
 */
class ExchangeRateTests extends AbstractIntegrationTest {

    /** Today, as the platform's clock sees it. The suite's clock is free-running. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    @Autowired
    private ScriptedRateSource source;

    @Autowired
    private ExchangeRateRefreshJob refresh;

    @Autowired
    private ExchangeRates rates;

    @Autowired
    private ExchangeRateRepository stored;

    @BeforeEach
    void aFreshTableAndScript() {
        source.reset();
        // The table is global and shared, like every other in this suite. Nothing else
        // writes to it, so clearing it is enough and there is no append-only trigger in the
        // way -- a rate is a public number that can be fetched again.
        stored.deleteAll();
    }

    // ------------------------------------------------------------------
    // The refresh
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a publication is stored once, whatever the hour")
    void refreshingTwiceInADayWritesOnce() {
        source.publishes(today(), Map.of("USD", "1.7000000000", "EUR", "1.9877000000"));

        assertThat(refresh.refresh()).isEqualTo(2);
        // §21.2 asks for an hourly cache and the source publishes daily. The second pass is
        // the eleven-out-of-twelve case, and it must cost two indexed reads rather than two
        // rows -- an UPSERT would keep the table at forty rows and lose the history.
        assertThat(refresh.refresh()).isZero();
        assertThat(stored.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a new publication is stored beside the old one rather than replacing it")
    void aNewPublicationIsKeptBesideTheOld() {
        source.publishes(today().minusDays(1), Map.of("USD", "1.7000000000"));
        refresh.refresh();
        source.publishes(today(), Map.of("USD", "1.7100000000"));

        assertThat(refresh.refresh()).isEqualTo(1);

        assertThat(stored.count())
                .as("the history is what answers 'what was the official rate the day this pledge was made'")
                .isEqualTo(2);
        assertThat(rates.available())
                .singleElement()
                .satisfies(quote -> assertThat(quote.rate()).isEqualByComparingTo("1.71"));
    }

    @Test
    @DisplayName("an unreachable source keeps the rates it already had, and does not fail the job")
    void anUnreachableSourceKeepsWhatItHad() {
        source.publishes(today(), Map.of("USD", "1.7000000000"));
        refresh.refresh();

        source.willBeUnavailable();

        // Not a throw. A ScheduledJob that throws is counted, backed off and eventually
        // marked DEAD -- which would mean a central bank's bad afternoon permanently
        // stopping the refresh, for a feature whose failure mode is a missing figure.
        assertThat(refresh.refresh()).isZero();
        assertThat(rates.available()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // The arithmetic
    // ------------------------------------------------------------------

    /**
     * <strong>The direction, which is the one mistake this feature can make.</strong>
     *
     * <p>One dollar is worth 1.70 manat, so ₼50.00 is <em>less</em> than fifty dollars:
     * 50 / 1.7 = 29.4117…, which rounds to $29.41. Multiplying instead would produce $85.00,
     * and on a rate near one — the euro at 1.9877, say — the two answers are close enough
     * that nobody notices until somebody pledges in lira.
     */
    @Test
    @DisplayName("an amount is divided by its rate, and rounded once at the end")
    void anAmountIsDividedByItsRate() {
        source.publishes(today(), Map.of("USD", "1.7000000000"));
        refresh.refresh();

        Approximation approximation =
                rates.approximate(Money.of(new BigDecimal("50.00"), "AZN"), "USD").orElseThrow();

        assertThat(approximation.approximate().amount()).isEqualByComparingTo("29.41");
        assertThat(approximation.approximate().currency()).isEqualTo("USD");
        // The exact amount travels beside it, and it is the one with the plain name. A caller
        // that received only the converted figure could put it on a receipt.
        assertThat(approximation.exact().amount()).isEqualByComparingTo("50.00");
        assertThat(approximation.rate()).isEqualByComparingTo("1.7");
    }

    /**
     * The lira, which is where rounding a rate stops being academic.
     *
     * <p>0.0354 manat per lira. ₼50.00 is 50 / 0.0354 = 1412.429… lira. A rate rounded to
     * two decimal places would be 0.04, giving 1250 — a thirteen per cent error, in the
     * direction that makes the campaign look cheaper than it is.
     */
    @Test
    @DisplayName("a rate is kept at full precision, not rounded like money")
    void aRateIsNotRoundedLikeMoney() {
        source.publishes(today(), Map.of("TRY", "0.0354000000"));
        refresh.refresh();

        Approximation approximation =
                rates.approximate(Money.of(new BigDecimal("50.00"), "AZN"), "TRY").orElseThrow();

        assertThat(approximation.approximate().amount()).isEqualByComparingTo("1412.43");
        assertThat(approximation.rate())
                .as("ten decimal places, and never MoneyRounding's two")
                .isEqualByComparingTo("0.0354");
    }

    @Test
    @DisplayName("an amount is not an approximation of itself")
    void theBaseCurrencyIsNotADisplayChoice() {
        source.publishes(today(), Map.of("USD", "1.7000000000"));
        refresh.refresh();

        // "₼50 ≈ ₼50" reads as a conversion that went wrong rather than one that was not
        // needed, so there is nothing to show and the caller is told so.
        assertThat(rates.approximate(Money.of(new BigDecimal("50.00"), "AZN"), "AZN")).isEmpty();
    }

    @Test
    @DisplayName("a currency this deployment does not offer produces nothing")
    void anUnofferedCurrencyProducesNothing() {
        source.publishes(today(), Map.of("USD", "1.7000000000"));
        refresh.refresh();

        // Gold. The source publishes it and `display-currencies` does not list it, so it is
        // filtered before it is ever stored -- a settings screen offering to price a campaign
        // in troy ounces is a screen nobody designed.
        assertThat(rates.approximate(Money.of(new BigDecimal("50.00"), "AZN"), "XAU")).isEmpty();
        assertThat(stored.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Degrading to absence
    // ------------------------------------------------------------------

    /**
     * §21.2 degrades to absence, never to a guess.
     *
     * <p>The age is measured on the publication date and not on the fetch, which is the
     * distinction that matters: a source answering every hour with last month's rates has a
     * fresh fetch and a stale rate, and it is the rate a reader is shown.
     */
    @Test
    @DisplayName("a rate older than the limit is not shown at all")
    void aStaleRateIsNotShownAtAll() {
        source.publishes(today().minusDays(30), Map.of("USD", "1.7000000000"));

        assertThat(refresh.refresh()).as("it is stored -- the history is kept").isEqualTo(1);

        assertThat(rates.approximate(Money.of(new BigDecimal("50.00"), "AZN"), "USD"))
                .as("a figure computed from a month-old rate is worse than no figure")
                .isEmpty();
        assertThat(rates.available())
                .as("and the settings screen must not offer a choice that would produce nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a currency with no rate at all is not offered")
    void aCurrencyNeverFetchedIsNotOffered() {
        source.publishes(today(), Map.of("USD", "1.7000000000"));
        refresh.refresh();

        assertThat(rates.available()).extracting(ExchangeRates.Quote::currency).containsExactly("USD");
        assertThat(rates.approximate(Money.of(new BigDecimal("50.00"), "AZN"), "EUR")).isEmpty();
    }

    // ------------------------------------------------------------------
    // §21.2's rate retention
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the rate a pledge should record is the rate, and nothing else")
    void theRateForAPledgeIsJustTheRate() {
        source.publishes(today(), Map.of("USD", "1.7000000000"));
        refresh.refresh();

        Optional<BigDecimal> rate = rates.rateFor("AZN", "USD");

        // V60 stores the rate alone: storing the converted amount as well would be storing a
        // figure that can disagree with its own inputs.
        assertThat(rate).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("1.7"));
        assertThat(rates.rateFor("AZN", "AZN")).as("no approximation was shown").isEmpty();
        assertThat(rates.rateFor("AZN", "XAU")).isEmpty();
    }

    @Test
    @DisplayName("the base currency is published, and is never one of the choices")
    void theBaseCurrencyIsPublished() {
        source.publishes(today(), Map.of("USD", "1.7000000000", "EUR", "1.9877000000"));
        refresh.refresh();

        // A client that hard-coded AZN would silently convert from the wrong currency the
        // day §21.2's phase 2 gives a campaign another one.
        assertThat(rates.baseCurrency()).isEqualTo("AZN");
        assertThat(rates.enabled()).isTrue();

        List<String> offered = rates.available().stream().map(ExchangeRates.Quote::currency).toList();
        assertThat(offered).containsExactlyInAnyOrder("USD", "EUR");
        // A currency priced in itself is 1 by definition, and offering it would put
        // "₼50 ≈ ₼50" on a screen.
        assertThat(offered).doesNotContain(rates.baseCurrency());
    }
}
