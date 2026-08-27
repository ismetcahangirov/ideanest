package az.ideanest.fx.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One rate as a source published it — issue #327.
 *
 * <p>The value a {@link RateSource} hands back, before anything has been stored. It is
 * separate from the entity so that the port can be implemented — and tested — without a
 * database, and so that a source adapter never decides what a row looks like.
 *
 * <p><strong>Already normalised to one unit.</strong> The Central Bank of Azerbaijan quotes
 * the rouble per hundred, so its adapter divides by the nominal before constructing this.
 * Carrying the nominal instead would push that division into every reader, and the reader
 * that forgot it would be out by a factor of a hundred on one currency.
 *
 * @param quoteCurrency the currency being priced
 * @param rate units of the source's base currency per ONE unit of {@code quoteCurrency}
 * @param publishedFor the day the source says it is in force from, read from the document
 *     rather than from the request — cbar.az answers a Sunday with Friday's publication
 */
public record PublishedRate(String quoteCurrency, BigDecimal rate, LocalDate publishedFor) {

    public PublishedRate {
        Objects.requireNonNull(quoteCurrency, "A rate prices a currency");
        Objects.requireNonNull(rate, "A rate is a number");
        Objects.requireNonNull(publishedFor, "A rate is in force from a day");

        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("A rate is positive, and this one is " + rate.toPlainString());
        }
    }
}
