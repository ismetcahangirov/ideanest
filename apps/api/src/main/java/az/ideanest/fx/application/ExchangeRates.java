package az.ideanest.fx.application;

import az.ideanest.fx.FxProperties;
import az.ideanest.fx.domain.ExchangeRate;
import az.ideanest.fx.infrastructure.ExchangeRateRepository;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.money.MoneyRounding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §21.2's display currency, as every other module sees it — issue #327.
 *
 * <h2>What it does</h2>
 *
 * Answers one question: given an amount the platform is going to charge, what is it roughly
 * worth in a currency the reader thinks in? And it declines to answer whenever the honest
 * answer is "we do not know".
 *
 * <h2>THE ARITHMETIC, AND WHY `Money` IS NOT ASKED TO DO IT</h2>
 *
 * {@code Money} refuses to combine two currencies, with a {@code CurrencyMismatchException},
 * deliberately: §21.2's rate is an approximation shown to a reader and is never the basis of
 * a collection. That refusal is correct and this class is built <strong>around</strong> it
 * rather than through it.
 *
 * <p>The division therefore happens on plain {@link BigDecimal} — a scalar operation on the
 * amount, the way {@code Money#percentage} works — and a {@code Money} is constructed from
 * the result at the end. Two {@code Money} values in different currencies never meet, and
 * {@link Approximation} is what comes back so that nothing downstream can mistake the result
 * for something chargeable.
 *
 * <p><strong>Rounded once, at the end, to the target currency's own minor unit.</strong>
 * {@code MoneyRounding.round} is what does it, so the display currency obeys §21.2's
 * {@code HALF_EVEN} exactly as every charged amount does. The rate itself is never rounded:
 * it is a ratio at ten decimal places, and taking it to two would make the lira's 0.0354
 * into 0.04 — a thirteen per cent error in every figure computed from it.
 *
 * <h2>EVERY ANSWER IS OPTIONAL, AND THAT IS THE FEATURE</h2>
 *
 * Six things produce no approximation, and all of them produce the same nothing:
 *
 * <ol>
 *   <li>the feature is switched off for this deployment;
 *   <li>the reader's currency is not one this deployment offers;
 *   <li>the reader's currency is the amount's own — "₼50 ≈ ₼50" reads as a conversion that
 *       went wrong rather than one that was not needed;
 *   <li>the amount is not in the base currency the source prices in;
 *   <li>no rate has ever been fetched for that currency;
 *   <li>the newest rate is older than {@code ideanest.fx.max-age}.
 * </ol>
 *
 * <p>A converted figure computed from a stale or invented rate is worse than no figure,
 * because a backer acts on it. There is no fallback rate anywhere in this class and there
 * must never be one.
 */
@Service
public class ExchangeRates {

    /**
     * The precision the division is carried out at.
     *
     * <p>Sixteen significant figures — a double's worth, on a value that is about to be
     * rounded to two decimal places anyway. It exists because {@code BigDecimal#divide} with
     * no context throws {@code ArithmeticException} on a non-terminating quotient, and
     * dividing by 1.7 is exactly that. Choosing the precision here rather than letting the
     * exception happen is the difference between a documented rounding and an outage on the
     * one currency whose rate does not divide evenly.
     */
    private static final MathContext DIVISION = new MathContext(16);

    private final ExchangeRateRepository rates;
    private final FxProperties properties;
    private final Clock clock;

    public ExchangeRates(ExchangeRateRepository rates, FxProperties properties, Clock clock) {
        this.rates = rates;
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether this deployment offers a display currency at all. */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * The currency every rate here is expressed in.
     *
     * <p>Published rather than assumed, so that a client cannot hard-code {@code AZN} and
     * silently convert from the wrong currency the day §21.2's phase 2 gives a campaign
     * another one.
     */
    public String baseCurrency() {
        return properties.baseCurrency();
    }

    /**
     * The currencies a reader may choose, and only the ones actually backed by a fresh rate.
     *
     * <p>Configuration says which are <em>offered</em>; this says which are
     * <em>available</em>, and the difference is the whole reason a settings screen can be
     * honest. A currency listed in configuration whose rate has not been fetched — a fresh
     * deployment, a source that has been down since Friday — is not in this list, so the
     * control never offers a choice that would produce no approximation.
     */
    @Transactional(readOnly = true)
    public List<Quote> available() {
        if (!properties.enabled()) {
            return List.of();
        }

        LocalDate oldestBelievable = oldestBelievable();
        List<Quote> quotes = new ArrayList<>();
        for (String currency : properties.displayCurrencies()) {
            newest(currency)
                    .filter(rate -> !rate.getPublishedFor().isBefore(oldestBelievable))
                    .ifPresent(rate -> quotes.add(
                            new Quote(rate.getQuoteCurrency(), rate.getRate(), rate.getPublishedFor(), rate.getFetchedAt())));
        }
        return List.copyOf(quotes);
    }

    /**
     * What {@code amount} is roughly worth in {@code displayCurrency}.
     *
     * @param amount what will actually be charged, in the campaign's currency
     * @param displayCurrency what the reader would rather read, or null when they have no
     *     preference — which is most readers, and answering it with an empty Optional rather
     *     than making every caller null-check first is the point of accepting it
     * @return the approximation, or empty for any of the six reasons in the class note
     */
    @Transactional(readOnly = true)
    public Optional<Approximation> approximate(Money amount, String displayCurrency) {
        if (amount == null || displayCurrency == null || !properties.offers(displayCurrency)) {
            return Optional.empty();
        }
        if (displayCurrency.equals(amount.currency())) {
            return Optional.empty();
        }
        if (!properties.baseCurrency().equals(amount.currency())) {
            /*
             * The source prices everything in one base currency, so an amount in anything
             * else would need two rates and a cross — which is a second rounding and a
             * second source of error on a number that is already an approximation. Phase 1
             * collects only in the base currency, so this cannot happen today; when phase 2
             * gives a campaign a currency of its own, the honest fix is a rate table with
             * that base in it rather than a division here.
             */
            return Optional.empty();
        }

        return newest(displayCurrency)
                .filter(rate -> !rate.getPublishedFor().isBefore(oldestBelievable()))
                .map(rate -> new Approximation(
                        amount,
                        convert(amount, rate.getQuoteCurrency(), rate.getRate()),
                        rate.getRate(),
                        rate.getPublishedFor()));
    }

    /**
     * The rate a pledge should record, or empty when it was shown no approximation.
     *
     * <p>§21.2: "the rate used is stored on the pledge, for audit". Separate from
     * {@link #approximate} because confirmation needs the number and not the converted
     * amount — V60 stores the rate alone, since storing both would be storing a figure that
     * can disagree with its own inputs.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> rateFor(String amountCurrency, String displayCurrency) {
        if (displayCurrency == null
                || !properties.offers(displayCurrency)
                || displayCurrency.equals(amountCurrency)
                || !properties.baseCurrency().equals(amountCurrency)) {
            return Optional.empty();
        }
        return newest(displayCurrency)
                .filter(rate -> !rate.getPublishedFor().isBefore(oldestBelievable()))
                .map(ExchangeRate::getRate);
    }

    /**
     * One unit of {@code quoteCurrency} is worth {@code rate} of the amount's currency, so
     * the amount is <strong>divided</strong>.
     *
     * <p>The direction is the one mistake this whole feature can make, it is invisible on a
     * rate near 1, and it is a factor-of-three error on the lira. It is asserted directly in
     * {@code ExchangeRateTests} against a figure computed by hand.
     */
    private static Money convert(Money amount, String quoteCurrency, BigDecimal rate) {
        BigDecimal converted = amount.amount().divide(rate, DIVISION);
        return Money.of(MoneyRounding.round(converted, quoteCurrency), quoteCurrency);
    }

    private Optional<ExchangeRate> newest(String quoteCurrency) {
        return rates.newest(properties.baseCurrency(), quoteCurrency);
    }

    /**
     * The oldest publication date still worth showing.
     *
     * <p>Measured on {@code published_for} rather than on {@code fetched_at}, and the
     * difference matters: a source that answers every hour with last Tuesday's rates has a
     * fresh {@code fetched_at} and a stale rate, and it is the rate a reader is shown.
     */
    private LocalDate oldestBelievable() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(properties.maxAge().toDays());
    }

    /**
     * One currency a reader may choose, and what it is worth.
     *
     * @param currency the currency being priced
     * @param rate units of the base currency per ONE unit of {@code currency}
     * @param publishedFor the day the source says it is in force from
     * @param fetchedAt when the platform last saw it. Both dates travel because they answer
     *     different questions — see V59
     */
    public record Quote(String currency, BigDecimal rate, LocalDate publishedFor, java.time.Instant fetchedAt) {
    }
}
