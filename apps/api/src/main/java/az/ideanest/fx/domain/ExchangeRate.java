package az.ideanest.fx.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One published rate — issue #327.
 *
 * <p><strong>One unit of {@link #getQuoteCurrency()} is worth {@link #getRate()} units of
 * {@link #getBaseCurrency()}.</strong> That sentence is the whole entity, and it is spelled
 * out here as well as in V59 because "base" and "quote" are the pair of words the subject is
 * least consistent about. With base {@code AZN} and quote {@code USD}, a rate of 1.7 means
 * one dollar costs 1.70 manat.
 *
 * <h2>The rate is not money and must never be rounded like it</h2>
 *
 * It is a ratio, stored at ten decimal places. {@code MoneyRounding} would take it to two
 * and put a per-cent error into every converted amount — 0.0354 for the lira would become
 * 0.04, a thirteen per cent error. Nothing in this class touches {@code Money}, and the
 * conversion in {@code ExchangeRates} rounds only at the end, once, to the target currency's
 * own minor unit.
 *
 * <h2>Immutable once written</h2>
 *
 * A rate is what a central bank published on a day. There is no setter, and re-fetching the
 * same day writes nothing: V59's unique index over
 * {@code (source, base, quote, published_for)} makes the hourly refresh idempotent, so
 * eleven of the twelve daily passes find the row they already wrote.
 */
@Entity
@Table(name = "exchange_rates")
public class ExchangeRate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "base_currency", nullable = false, updatable = false)
    private String baseCurrency;

    @Column(name = "quote_currency", nullable = false, updatable = false)
    private String quoteCurrency;

    @Column(name = "rate", nullable = false, updatable = false)
    private BigDecimal rate;

    @Column(name = "published_for", nullable = false, updatable = false)
    private LocalDate publishedFor;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    protected ExchangeRate() {
        // JPA.
    }

    private ExchangeRate(
            String source,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            LocalDate publishedFor,
            Instant fetchedAt) {

        this.id = Identifiers.newIdentifier();
        this.source = source;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.publishedFor = publishedFor;
        this.fetchedAt = fetchedAt;
    }

    /**
     * A rate as it was published.
     *
     * @param rate units of {@code baseCurrency} per ONE unit of {@code quoteCurrency},
     *     already divided by whatever nominal the source quoted it at
     * @throws IllegalArgumentException on a rate that is not positive, or a pair of
     *     identical currencies. Both are refused by V59 as well; refusing them here is what
     *     turns a constraint violation at flush time into a message naming the value
     */
    public static ExchangeRate published(
            RateSource source,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            LocalDate publishedFor,
            Instant fetchedAt) {

        Objects.requireNonNull(source, "A rate comes from somewhere");
        Objects.requireNonNull(rate, "A rate is a number");
        Objects.requireNonNull(publishedFor, "A rate is in force from a day");
        Objects.requireNonNull(fetchedAt, "A rate was seen at a moment");

        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("A rate is positive, and this one is " + rate.toPlainString());
        }
        if (Objects.equals(baseCurrency, quoteCurrency)) {
            throw new IllegalArgumentException(
                    "A currency priced in itself is 1 by definition and is not stored: " + baseCurrency);
        }

        return new ExchangeRate(
                source.name(),
                baseCurrency,
                quoteCurrency,
                rate,
                publishedFor,
                // Microseconds, like every other instant in this schema: PostgreSQL's
                // timestamptz keeps six digits, so a nanosecond value read back does not
                // equal the one written and every test that compares them is subtly wrong.
                fetchedAt.truncatedTo(ChronoUnit.MICROS));
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    /** Units of the base currency per ONE unit of the quote currency. */
    public BigDecimal getRate() {
        return rate;
    }

    /** The day the source says this is in force from, read from the document. */
    public LocalDate getPublishedFor() {
        return publishedFor;
    }

    /** When the platform last saw it, which is a different question from which rate it is. */
    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
