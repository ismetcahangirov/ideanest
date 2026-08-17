package az.ideanest.shared.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * An amount of money, and what currency it is in. <strong>The only one.</strong>
 *
 * <p>Every goal, reward price, pledge amount, fee, and payout in the backend is this
 * type. #133 exists because the alternative — a {@link BigDecimal} and a
 * {@code String currency} travelling next to each other, added up by whichever
 * service happens to hold them — puts the rounding decision, the currency check, and
 * the wire format in as many places as there are call sites, and each of them is
 * free to get it wrong differently.
 *
 * <h2>The rules, and where they live</h2>
 *
 * <ul>
 *   <li><strong>Rounding is stated once</strong>, in {@link MoneyRounding}: HALF_EVEN,
 *       at the currency's minor unit. Nothing here chooses a mode.</li>
 *   <li><strong>Input is refused, not rounded.</strong> An amount with a place the
 *       currency does not have — 5000.555 manat — is a different amount from any this
 *       type can hold, and rounding it silently would charge a card a figure nobody
 *       typed. Only values this class computes are rounded, and only where a product
 *       or a share can need it.</li>
 *   <li><strong>Two currencies are never combined.</strong> Every binary operation
 *       throws {@link CurrencyMismatchException} rather than coercing. §21.2 makes a
 *       conversion rate an approximation shown to a user, never the basis of a
 *       collection, so there is no arithmetic between currencies to fall back on.</li>
 *   <li><strong>The amount crosses the wire as a string.</strong> §10.3 requires it and
 *       {@code CLAUDE.md} repeats it: a JSON number is an IEEE 754 double in every
 *       mainstream parser. {@link MoneySerializer} and {@link MoneyDeserializer} are
 *       attached to the type here rather than registered with one {@code ObjectMapper},
 *       so the guarantee holds for the Spring mapper, for the idempotency fingerprint
 *       in {@code IdempotentRequests}, and for a mapper somebody constructs in a
 *       test.</li>
 *   <li><strong>Division is not offered.</strong> {@link #allocate(int)} is, because
 *       dividing money loses or invents minor units and allocation cannot: the parts
 *       always add up to the whole.</li>
 * </ul>
 *
 * <p><strong>Persistence.</strong> §7.2 stores an amount as {@code numeric(14,2)} and
 * the currency as a separate column shared by every amount on the row — a
 * {@code pledges} row has five amounts and one currency. So a {@code Money} is
 * assembled at the edge of the entity, with {@link #of} or {@link #orNull}, rather
 * than mapped as an embeddable pair; {@link MoneyAmountConverter} is what holds the
 * amount column to this type's rules on the way in and out.
 */
@JsonDeserialize(using = MoneyDeserializer.class)
@JsonSerialize(using = MoneySerializer.class)
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    /**
     * What §7.3's {@code numeric(14,2)} holds.
     *
     * <p>Kept as an alias of {@link MoneyRounding#COLUMN_SCALE} rather than a second
     * literal 2, so that callers already written against {@code Money.SCALE} share the
     * one definition. The scale of a particular amount is its <em>currency's</em>, which
     * is {@link MoneyRounding#scaleOf(String)} and is not always this.
     */
    public static final int SCALE = MoneyRounding.COLUMN_SCALE;

    public Money {
        Objects.requireNonNull(amount, "An amount is required");

        currency = MoneyRounding.currencyCode(currency);
        amount = MoneyRounding.exact(amount, currency);
    }

    // ------------------------------------------------------------------
    // Making one
    // ------------------------------------------------------------------

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    /**
     * The same amount, or null when there is nothing to describe.
     *
     * <p>A null-tolerant factory rather than null checks at every call site: a draft
     * campaign has no goal yet, and every mapping from an entity to a response has to
     * handle that.
     */
    public static Money orNull(BigDecimal amount, String currency) {
        return amount == null ? null : new Money(amount, currency);
    }

    /** Nothing, in a currency. Not the same as no amount at all — see {@link #orNull}. */
    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * An amount given in the currency's smallest unit: qapik, cents, whole yen.
     *
     * <p>What a payment provider speaks. Doing the conversion here, once, is what
     * stops each integration inventing its own factor of a hundred — and getting it
     * wrong for a currency that has no minor unit.
     */
    public static Money ofMinorUnits(long minorUnits, String currency) {
        String code = MoneyRounding.currencyCode(currency);
        return new Money(BigDecimal.valueOf(minorUnits, MoneyRounding.scaleOf(code)), code);
    }

    /**
     * Everything added up, in the currency named.
     *
     * <p>The currency is a parameter because a sum of nothing still has one: a
     * campaign with no pledges has raised zero manat, not "no answer". A member in
     * another currency is a {@link CurrencyMismatchException} rather than a skipped
     * row.
     */
    public static Money sum(String currency, Collection<Money> amounts) {
        Objects.requireNonNull(amounts, "There is nothing to add up");

        Money total = zero(currency);
        for (Money amount : amounts) {
            total = total.plus(amount);
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------

    /** Exact: two amounts at the same scale cannot need rounding to add. */
    public Money plus(Money other) {
        return new Money(amount.add(amountOf(other)), currency);
    }

    /**
     * Exact, and it may go below zero.
     *
     * <p>A refund, a reversal, and a chargeback are negative amounts, and §7.2 has
     * ledger entries to store them. Clamping at zero here would make the type unable
     * to express a row the platform has to write.
     */
    public Money minus(Money other) {
        return new Money(amount.subtract(amountOf(other)), currency);
    }

    /** The same amount on the other side of zero. */
    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    /** How much, regardless of direction. */
    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    /**
     * A price times a quantity. Exact — a whole factor cannot produce a new decimal
     * place — so nothing is rounded and nothing can be lost.
     */
    public Money times(long factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    /**
     * Times a factor that may have decimals of its own, rounded <strong>once</strong>
     * by {@link MoneyRounding}.
     *
     * <p>Once matters: rounding a chain of multiplications at every step accumulates
     * the error that HALF_EVEN exists to avoid. A caller with two factors multiplies
     * the factors, not the money.
     */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "A factor is required");
        return new Money(MoneyRounding.round(amount.multiply(factor), currency), currency);
    }

    /**
     * A rate applied to this amount: {@code percentage(new BigDecimal("5"))} is 5%.
     *
     * <p>§5.2's fees are percentages, and this is the one place they are computed.
     * The division by a hundred is exact — a decimal point moves — so the single
     * rounding is of the result, not of an intermediate.
     */
    public Money percentage(BigDecimal percent) {
        Objects.requireNonNull(percent, "A rate is required");
        return new Money(MoneyRounding.round(amount.multiply(percent).movePointLeft(2), currency), currency);
    }

    // ------------------------------------------------------------------
    // Splitting
    // ------------------------------------------------------------------

    /**
     * This amount split into equal parts, <strong>with the remainder handed out</strong>.
     *
     * <p>The failure this replaces: 0.05 divided three ways is 0.0166…, each share
     * rounds to 0.02, and the parts come to 0.06 — a qapik of money that was never
     * collected, in a payout that will not reconcile. Splitting the other way loses
     * one instead. Here the whole is converted to minor units, divided, and the
     * remainder is given out one minor unit at a time to the earliest parts, so the
     * parts add up to the whole for every input and no part is more than one minor
     * unit from another.
     *
     * <p>Earliest rather than random or largest-share: the order is documented and
     * repeatable, so re-running a split produces the same answer as the split that was
     * paid out.
     *
     * @throws IllegalArgumentException when there is not at least one part
     */
    public List<Money> allocate(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("An amount is split into at least one part, not " + parts);
        }

        long[] equal = new long[parts];
        Arrays.fill(equal, 1L);
        return allocate(equal);
    }

    /**
     * This amount split in proportion to the weights, with the same guarantee: the
     * parts add up to the whole.
     *
     * <p>For a split that is not even — a fee schedule, a revenue share, §9.5's
     * distribution between a creator, the platform, the provider, and a tax account.
     * A weight of zero takes nothing and is not handed a remainder; a negative weight
     * is refused, because a share of a payout that is owed backwards is a different
     * transaction and not a smaller share of this one.
     *
     * @throws IllegalArgumentException when there are no weights, when one is
     *     negative, or when they are all zero — an all-zero split has no proportion in
     *     it, and answering with zeroes would lose the whole amount
     */
    public List<Money> allocate(long... weights) {
        Objects.requireNonNull(weights, "A split needs weights");
        if (weights.length == 0) {
            throw new IllegalArgumentException("An amount is split into at least one part");
        }

        BigInteger totalWeight = BigInteger.ZERO;
        for (long weight : weights) {
            if (weight < 0) {
                throw new IllegalArgumentException("A share of an amount cannot be negative, and one is " + weight);
            }
            totalWeight = totalWeight.add(BigInteger.valueOf(weight));
        }
        if (totalWeight.signum() == 0) {
            throw new IllegalArgumentException("A split whose weights are all zero has no proportion in it");
        }

        // Minor units, as integers: the arithmetic below is exact in a way it could
        // not be at a decimal scale, and the invariant -- the parts sum to the whole
        // -- is then a property of integer division rather than of a rounding mode.
        BigInteger minorUnits = amount.unscaledValue();
        BigInteger[] shares = new BigInteger[weights.length];
        BigInteger distributed = BigInteger.ZERO;
        for (int part = 0; part < weights.length; part++) {
            // Truncating division, which is toward zero on both signs, so the
            // remainder below carries the sign of the whole and a refund splits the
            // way the charge it reverses did.
            shares[part] = minorUnits.multiply(BigInteger.valueOf(weights[part])).divide(totalWeight);
            distributed = distributed.add(shares[part]);
        }

        // Strictly smaller than the number of parts that have a weight at all, since
        // each truncation discards less than one minor unit and a zero weight
        // discards nothing. So one pass hands out all of it.
        BigInteger remaining = minorUnits.subtract(distributed).abs();
        BigInteger step = BigInteger.valueOf(minorUnits.subtract(distributed).signum());
        for (int part = 0; part < weights.length && remaining.signum() > 0; part++) {
            if (weights[part] == 0) {
                continue;
            }
            shares[part] = shares[part].add(step);
            remaining = remaining.subtract(BigInteger.ONE);
        }

        List<Money> allocated = new ArrayList<>(weights.length);
        for (BigInteger share : shares) {
            allocated.add(new Money(new BigDecimal(share, amount.scale()), currency));
        }
        return List.copyOf(allocated);
    }

    // ------------------------------------------------------------------
    // Asking about one
    // ------------------------------------------------------------------

    /** This amount in the currency's smallest unit, exactly, or an overflow rather than a wrong number. */
    public long minorUnits() {
        return amount.unscaledValue().longValueExact();
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public Money min(Money other) {
        return isLessThan(other) ? this : other;
    }

    public Money max(Money other) {
        return isGreaterThan(other) ? this : other;
    }

    /**
     * By value, within one currency.
     *
     * <p>Consistent with {@code equals}, which is what lets an amount behave the same
     * in a {@code TreeSet} as in a {@code HashSet}: the constructor normalises the
     * scale, so 5000 and 5000.00 are one value rather than two that compare equal and
     * hash differently.
     *
     * @throws CurrencyMismatchException when the two amounts are in different
     *     currencies. Deliberate, and the reason this ordering is not a
     *     {@code Comparator} the platform hands to a sort of mixed data: "which is
     *     bigger, 10 AZN or 10 USD" has no answer that is not a made-up rate
     */
    @Override
    public int compareTo(Money other) {
        return amount.compareTo(amountOf(other));
    }

    /** The amount and its currency, because half the money bugs in a log are an assumed currency. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    /** The other amount, once it is established that it is in this currency. */
    private BigDecimal amountOf(Money other) {
        Objects.requireNonNull(other, "An amount is required");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
        return other.amount;
    }
}
