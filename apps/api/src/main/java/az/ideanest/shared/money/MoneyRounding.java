package az.ideanest.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * <strong>The rounding rule. All of it. There is no other.</strong>
 *
 * <p>#133 asks for decimal arithmetic end to end "with rounding rules stated once
 * and enforced", and this class is the "once". Every scale and every
 * {@link RoundingMode} in the backend comes from here: {@link Money} calls it,
 * {@link MoneyAmountConverter} calls it, and a caller that needs to round money
 * without going through {@link Money} calls it too. A second {@code setScale} with
 * a mode of its own somewhere else is the bug this class exists to make visible in
 * review.
 *
 * <h2>The mode is HALF_EVEN</h2>
 *
 * <p>Because the values being rounded are <em>computed</em> ones. §5.2's fees are a
 * 5% platform fee and a per-collection processing fee; §9.5 splits a collection
 * between an escrow, a creator, the platform, the provider, and a tax account.
 * {@code HALF_UP} resolves every halfway case away from zero, so across a
 * campaign's worth of fees the bias always favours the same party, and the party it
 * favours is whichever side of the split the code happened to compute. HALF_EVEN
 * ("banker's rounding") splits halfway cases evenly between up and down, which is
 * why it is the default for financial arithmetic and the default for
 * {@code MathContext.DECIMAL128}.
 *
 * <p><strong>Rounding is for computed values only.</strong> An amount that arrived
 * from a client or from the database is not rounded — {@link #exact} refuses it
 * instead, because rounding somebody's input silently changes a number they typed
 * and can see. That asymmetry is the whole design: {@link #round} for arithmetic
 * this code performed, {@link #exact} for everything that came from outside.
 *
 * <h2>The scale is the currency's minor unit</h2>
 *
 * <p>Two decimal places for AZN, USD, EUR, TRY, and RUB — §21.2's phase 1 and
 * phase 2 currencies — and zero for a currency that has no minor unit at all,
 * because 100.5 yen is not an amount of money and a fixed scale of 2 would let one
 * be constructed. Taken from {@link Currency} rather than hard-coded so that the
 * table is the JVM's ISO 4217 data and not a list somebody has to maintain.
 *
 * <p><strong>A currency with more minor units than the columns hold is refused.</strong>
 * §7.3 fixes every money column at {@code numeric(14,2)}, so a three-decimal
 * currency such as KWD would be rounded by PostgreSQL on the way in — silently,
 * inside a charge. Refusing it here turns a data-loss bug into a startup-time
 * argument about §7.3, which is where that decision belongs.
 */
public final class MoneyRounding {

    /**
     * The one rounding mode in the backend.
     *
     * <p>Referenced rather than repeated: a caller that writes
     * {@code RoundingMode.HALF_EVEN} itself has made a second decision that happens
     * to agree, and nothing stops the two from drifting apart.
     */
    public static final RoundingMode MODE = RoundingMode.HALF_EVEN;

    /**
     * What {@code numeric(14,2)} holds, per §7.3.
     *
     * <p>The upper bound on any currency's minor unit, and the scale used for an
     * ISO-shaped code the JVM has no entry for.
     */
    public static final int COLUMN_SCALE = 2;

    /** ISO 4217 alphabetic codes. The database checks the same shape. */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private MoneyRounding() {
    }

    /**
     * The normalised form of a currency code, or a refusal.
     *
     * <p>Trimmed and upper-cased so that {@code "azn"} and {@code "AZN"} are one
     * currency rather than two, which they have to be for any comparison between
     * two amounts to mean anything.
     *
     * @throws IllegalArgumentException when it is not three letters
     */
    public static String currencyCode(String currency) {
        Objects.requireNonNull(currency, "A currency is required");

        String code = currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(code).matches()) {
            throw new IllegalArgumentException("A currency is a three-letter ISO 4217 code, not " + currency);
        }
        return code;
    }

    /**
     * How many decimal places an amount in this currency has.
     *
     * @throws IllegalArgumentException when the code is malformed, or when the
     *     currency has more minor units than §7.3's columns can hold
     */
    public static int scaleOf(String currency) {
        String code = currencyCode(currency);
        int minorUnits = minorUnitsOf(code);
        if (minorUnits > COLUMN_SCALE) {
            throw new IllegalArgumentException("A " + code + " amount has " + minorUnits
                    + " decimal places and every money column is numeric(14," + COLUMN_SCALE
                    + "), which would round it. §7.3 has to change before " + code + " can be charged");
        }
        return minorUnits;
    }

    /**
     * A computed amount, rounded to the currency's scale by {@link #MODE}.
     *
     * <p>For results of arithmetic only. Rounding here is deliberate and the mode is
     * the platform's, so the call site does not choose one.
     */
    public static BigDecimal round(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "An amount is required");
        return amount.setScale(scaleOf(currency), MODE);
    }

    /**
     * An amount that came from outside, padded to the currency's scale, or a
     * refusal if padding is not enough.
     *
     * <p>{@code 5000} becomes {@code 5000.00} — a client that sent the first must
     * not be told the value changed when it reads the second back. {@code 5000.555}
     * is refused: it is a different amount, not another spelling of one, and
     * rounding it would charge a card a figure nobody typed.
     *
     * @throws IllegalArgumentException when the amount has more decimal places than
     *     the currency has minor units
     */
    public static BigDecimal exact(BigDecimal amount, String currency) {
        String code = currencyCode(currency);
        return exactAt(amount, scaleOf(code), "An amount in " + code);
    }

    /**
     * The same, at the scale of the column rather than of a currency.
     *
     * <p>For {@link MoneyAmountConverter}, which converts one column and cannot see
     * the {@code currency} column beside it. §7.3 fixes every money column at
     * {@code numeric(14,2)} and {@link #scaleOf} refuses any currency that needs more,
     * so this bound holds for every currency the platform can charge in.
     */
    public static BigDecimal exactAtColumnScale(BigDecimal amount) {
        return exactAt(amount, COLUMN_SCALE, "A money column");
    }

    private static BigDecimal exactAt(BigDecimal amount, int scale, String subject) {
        Objects.requireNonNull(amount, "An amount is required");

        // stripTrailingZeros first, so that the distinction is between amounts and
        // not between spellings of one: "5000.000" is the same amount and is
        // accepted, "5000.555" is a different one and is refused.
        if (amount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException(subject + " has at most " + scale + " decimal places, and "
                    + amount.toPlainString() + " has more");
        }
        // UNNECESSARY because the check above has established that nothing has to
        // be discarded. If it ever throws, the check above is wrong and a silent
        // rounding would be the alternative.
        return amount.setScale(scale, RoundingMode.UNNECESSARY);
    }

    /**
     * The JVM's ISO 4217 answer, with the two cases it has that money does not.
     *
     * <p>An unknown code is not an error here: the database constrains a currency to
     * three letters and nothing more, so a well-formed code has to be usable, and
     * the column's own scale is the only defensible answer for one the JVM has never
     * heard of. A pseudo-currency such as XDR reports −1 fraction digits, meaning
     * "not a currency amount at all", and is treated the same way rather than as a
     * scale of zero — reading a negative scale as "round to whole units" would
     * quietly discard every minor unit.
     */
    private static int minorUnitsOf(String code) {
        try {
            int digits = Currency.getInstance(code).getDefaultFractionDigits();
            return digits < 0 ? COLUMN_SCALE : digits;
        } catch (IllegalArgumentException unknownToTheJvm) {
            return COLUMN_SCALE;
        }
    }
}
