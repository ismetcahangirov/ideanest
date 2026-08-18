package az.ideanest.analytics.domain;

import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What proportion of a campaign's attributed value each source brought, as
 * percentages that add up to exactly one hundred.
 *
 * <p>§4.7's CD-03 asks for "pledge count, value, and share". The first two are a
 * {@code COUNT} and a {@code SUM}; the third is a division, and a division of money is
 * where a report acquires the errors nobody can account for. Rounding each source's
 * percentage on its own is the obvious implementation and it produces shares that come
 * to 99.99 — a creator who notices that stops trusting the two columns to the left of
 * it, which are correct.
 *
 * <h2>The same argument {@code Money.allocate} makes, one column over</h2>
 *
 * <p>{@link Money#allocate(long...)} exists because dividing money loses or invents
 * minor units and allocation cannot. A share is that problem with a different unit: the
 * whole is one hundred percent, the parts are the sources, and the parts have to add up
 * to the whole for the same reason. So the arithmetic is the same — convert the whole
 * to integer units, divide, and hand the remainder out — and it is written here rather
 * than borrowed from {@code Money} because a percentage is not an amount of money and
 * calling {@code Money.of("100.00", "AZN").allocate(...)} would produce the right
 * numbers wearing the wrong type. The currency of a share is not the currency of the
 * pledges.
 *
 * <p>Integer units throughout, in {@link BigInteger}: the guarantee that the shares sum
 * to one hundred is then a property of integer division rather than of a rounding mode
 * that happened to agree with itself.
 *
 * <h2>Largest remainder, ties to the earliest</h2>
 *
 * <p>The unit that division could not place goes to the source whose truncated share
 * lost the most, which is the standard apportionment rule and the one that keeps every
 * share within a hundredth of its exact value. A tie goes to the earlier source, for
 * {@code Money.allocate}'s reason: the order is documented and repeatable, so reading
 * the report twice gives the same answer rather than one that depends on how the rows
 * happened to sort.
 */
public final class ReferralShares {

    /** Two decimal places of percent, so the whole is 100.00 and the unit is 0.01. */
    private static final int SCALE = 2;

    /** 100.00, as the integer count of those units. */
    private static final BigInteger WHOLE = BigInteger.valueOf(10_000);

    private ReferralShares() {
    }

    /**
     * Each value's percentage of their total, in the order they were given.
     *
     * @param values one attributed total per source. All in one currency —
     *     {@link Money#sum} refuses a mixture with
     *     {@link az.ideanest.shared.money.CurrencyMismatchException}, which is the
     *     right refusal rather than a shortcoming: there is no exchange rate that
     *     makes "40% of the value" true of a mixed total
     * @return a percentage per value at two decimal places, summing to exactly
     *     {@code 100.00} — or all zeroes when the total is zero, because a share of
     *     nothing is nothing and not a division by zero. Empty for no values at all
     * @throws IllegalArgumentException when a value is negative. An attributed pledge
     *     never is, and a share of a negative part has no meaning as a proportion of a
     *     total that includes it — so this is a bug found rather than a number shipped
     */
    public static List<BigDecimal> of(List<Money> values) {
        Objects.requireNonNull(values, "There are no values to take a share of");
        if (values.isEmpty()) {
            return List.of();
        }

        long[] weights = new long[values.size()];
        for (int part = 0; part < values.size(); part++) {
            Money value = values.get(part);
            if (value.isNegative()) {
                throw new IllegalArgumentException("An attributed value cannot be negative, and one is " + value);
            }
            weights[part] = value.minorUnits();
        }

        // Refuses a mixture of currencies, which is the point of routing the total
        // through Money rather than adding the weights up here. The currency is the
        // first value's; a second one in another currency never reaches the division.
        Money total = Money.sum(values.get(0).currency(), values);
        if (total.isZero()) {
            // Every share of nothing is nothing. Returned rather than thrown: a
            // campaign whose only attributed pledges were later reversed is a real
            // state, and a report that failed to render for it would be worse than one
            // full of zeroes.
            return zeroes(values.size());
        }

        return allocate(weights, BigInteger.valueOf(total.minorUnits()));
    }

    /**
     * {@code WHOLE} units divided in proportion to the weights, remainder included.
     *
     * <p>Truncating division first, so no share is ever above its exact value, then one
     * unit each to the largest remainders until the whole is spent. There are strictly
     * fewer units left over than there are non-zero weights — each truncation discards
     * less than one unit — so one pass places all of them.
     */
    private static List<BigDecimal> allocate(long[] weights, BigInteger totalWeight) {
        BigInteger[] shares = new BigInteger[weights.length];
        BigInteger[] remainders = new BigInteger[weights.length];
        BigInteger placed = BigInteger.ZERO;

        for (int part = 0; part < weights.length; part++) {
            BigInteger scaled = WHOLE.multiply(BigInteger.valueOf(weights[part]));
            BigInteger[] shareAndRemainder = scaled.divideAndRemainder(totalWeight);
            shares[part] = shareAndRemainder[0];
            remainders[part] = shareAndRemainder[1];
            placed = placed.add(shares[part]);
        }

        int remaining = WHOLE.subtract(placed).intValueExact();
        for (Integer part : byLargestRemainder(remainders, remaining)) {
            shares[part] = shares[part].add(BigInteger.ONE);
        }

        List<BigDecimal> percentages = new ArrayList<>(weights.length);
        for (BigInteger share : shares) {
            percentages.add(new BigDecimal(share, SCALE));
        }
        return List.copyOf(percentages);
    }

    /**
     * Which sources get the units that division could not place: the largest remainders
     * first, and the earlier source on a tie.
     */
    private static List<Integer> byLargestRemainder(BigInteger[] remainders, int howMany) {
        Integer[] order = new Integer[remainders.length];
        for (int part = 0; part < remainders.length; part++) {
            order[part] = part;
        }
        // Stable, so the natural order of the array is what breaks a tie and the
        // earlier source wins it.
        Arrays.sort(order, (left, right) -> remainders[right].compareTo(remainders[left]));
        return List.of(order).subList(0, howMany);
    }

    private static List<BigDecimal> zeroes(int parts) {
        List<BigDecimal> shares = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            shares.add(BigDecimal.ZERO.setScale(SCALE));
        }
        return List.copyOf(shares);
    }
}
