package az.ideanest.shared;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money, and what currency it is in.
 *
 * <p><strong>The amount crosses the wire as a string.</strong> §10.3 requires it
 * and {@code CLAUDE.md} repeats it: a JSON number is an IEEE 754 double in every
 * mainstream parser, so serialising {@code 5000.00} as a number invites a client
 * to read it into a value that cannot represent it exactly. The
 * {@link JsonFormat} annotation is what makes that true for every response
 * carrying this type, rather than for the ones somebody remembered.
 *
 * <p><strong>Two decimal places, and no rounding.</strong> The columns are
 * {@code numeric(14,2)}, so a third decimal place has nowhere to go. PostgreSQL
 * would round it silently; this refuses it instead. Silently turning a creator's
 * 5000.555 into 5000.56 is a small error today and an unreconcilable ledger the
 * first time it happens on a charge.
 *
 * <p>Lives in {@code shared} because goals, reward prices, pledges, fees, and
 * payouts are all money, and a second definition of the wire format is how one
 * endpoint ends up answering {@code "5000"} and another {@code "5000.00"} for the
 * same value.
 */
public record Money(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency) {

    /** ISO 4217 alphabetic codes. The database checks the same shape. */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** What {@code numeric(14,2)} can hold, and what every price in the platform is quoted to. */
    public static final int SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "An amount is required");
        Objects.requireNonNull(currency, "A currency is required");

        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("A currency is a three-letter ISO 4217 code");
        }

        // stripTrailingZeros first, so that the distinction is between amounts
        // and not between spellings of one: "5000.000" is the same amount and is
        // accepted, "5000.555" is a different one and is refused rather than
        // quietly rounded to something the creator did not type.
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("An amount has at most " + SCALE + " decimal places");
        }
        // Padded to the scale of the column, so that a client which sent 5000 is
        // answered "5000.00" and one comparing what it sent with what it got back
        // is not told the value changed. UNNECESSARY because the check above has
        // already established that nothing has to be discarded.
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    /**
     * The same amount, or null when there is nothing to describe.
     *
     * <p>A null-tolerant factory rather than null checks at every call site: a
     * draft campaign has no goal yet, and every mapping from an entity to a
     * response has to handle that.
     */
    public static Money orNull(BigDecimal amount, String currency) {
        return amount == null ? null : new Money(amount, currency);
    }
}
