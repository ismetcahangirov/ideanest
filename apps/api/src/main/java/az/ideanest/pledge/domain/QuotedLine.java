package az.ideanest.pledge.domain;

import az.ideanest.reward.application.ShippingRate;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One thing a backer selected, priced and counted.
 *
 * <p>A reward tier and an add-on are the same shape here, because §7.2 makes them
 * the same row: an add-on is a {@code reward_tiers} row with {@code is_addon}
 * true (V7). What separates them is where they sit on a {@link PledgeSelection} —
 * one reward, any number of add-ons — and not what they are. Deciding which is
 * which is a query, so it belongs to whatever loads the tiers rather than to the
 * arithmetic.
 *
 * <p><strong>Plain values, no entity.</strong> {@code RewardTier} lives in the
 * reward module's {@code domain} package and this module may not reach it — see
 * {@code az/ideanest/package-info.java}, and {@code ModuleBoundaryTests}, which
 * fails the build over it. That constraint is doing something useful here: a
 * quote assembled out of a price, a currency, a quantity, and a rate can be
 * stated in a unit test in one line, which is what makes the case a
 * {@code double} would get wrong cheap enough to pin.
 *
 * @param unitAmount what one of these costs. The tier's {@code amount}, never a
 *     total — {@link #lineAmount()} does the multiplication, so that a caller
 *     cannot pass a pre-multiplied figure and a quantity as well
 * @param quantity how many. Always 1 for a reward tier: §7.2 gives {@code pledges}
 *     a single {@code reward_tier_id}, so there is nowhere to record two
 * @param shipped whether a per-country rate means anything for this line, which is
 *     {@code ShippingType.isShipped()} — {@code DOMESTIC} or {@code INTERNATIONAL},
 *     and not {@code NONE}, {@code DIGITAL}, or {@code LOCAL_PICKUP}. A boolean
 *     rather than a copy of that enum: the enum belongs to the reward module and a
 *     second one here would be a second answer to what {@code DIGITAL} means, free
 *     to disagree with the first the next time §7.2 gains a value
 * @param shippingRate the {@code shipping_rules} row for the destination this
 *     pledge is going to, or null when the creator has priced no such row.
 *     <strong>Null on a shipped line is a refusal, not a zero</strong>, and
 *     {@link PledgeQuote} is where it is refused, because that is where the
 *     destination is known and can be named. On a line that is not shipped the
 *     rate is ignored rather than refused: a tier switched to {@code DIGITAL}
 *     keeps whatever rules it had, and §7.2 is explicit that no rule applies to it
 * @param unitWeightGrams what one of these weighs, summed by the caller from the
 *     tier's items and their quantities — V7 put the weight on the item and said
 *     that a tier's weight is a query rather than a column, and #77 is where that
 *     query is finally run. Zero when the creator recorded no weights, which is
 *     the majority of campaigns and is not an error: see {@link ShippingRate#costFor}
 */
public record QuotedLine(
        BigDecimal unitAmount,
        String currency,
        int quantity,
        boolean shipped,
        ShippingRate shippingRate,
        long unitWeightGrams) {

    public QuotedLine {
        Objects.requireNonNull(unitAmount, "A line has a price");

        // Through Money, which is where "two decimal places, and no rounding"
        // is already stated and enforced. Restating it here would be a second
        // rounding rule, and the whole point of having one is that a price
        // accepted by a reward is accepted by the pledge quoted from it.
        Money price = Money.of(unitAmount, currency);
        unitAmount = price.amount();
        currency = price.currency();

        if (unitAmount.signum() < 0) {
            throw new IllegalArgumentException("A line cannot cost less than nothing");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("A selected line has a quantity of at least 1, not " + quantity);
        }
        if (unitWeightGrams < 0) {
            throw new IllegalArgumentException("A line cannot weigh less than nothing");
        }
    }

    /** Something a backer keeps, not something posted: a digital file, a credit, a collection in person. */
    public static QuotedLine notShipped(BigDecimal unitAmount, String currency, int quantity) {
        // Weightless by construction rather than by omission: nothing that is not
        // posted has a shipping weight, whatever its items say they weigh.
        return new QuotedLine(unitAmount, currency, quantity, false, null, 0L);
    }

    /**
     * Something posted to the destination the pledge names, priced by a flat rate.
     *
     * @param shippingRate the rate for <em>that</em> destination, or null when the
     *     creator has not priced it. Resolving it is the caller's job because the
     *     rules are a table in another module; checking that the caller resolved it
     *     for the right country is {@link PledgeQuote}'s, and it does
     */
    public static QuotedLine shipped(BigDecimal unitAmount, String currency, int quantity, ShippingRate shippingRate) {
        return new QuotedLine(unitAmount, currency, quantity, true, shippingRate, 0L);
    }

    /**
     * The same, for a tier whose items carry recorded weights.
     *
     * <p>A separate factory rather than a fifth argument on the one above, so that
     * a caller which has not summed the tier's item weights cannot accidentally
     * claim it did by passing a zero.
     */
    public static QuotedLine shipped(
            BigDecimal unitAmount, String currency, int quantity, ShippingRate shippingRate, long unitWeightGrams) {

        return new QuotedLine(unitAmount, currency, quantity, true, shippingRate, unitWeightGrams);
    }

    /**
     * Price times quantity.
     *
     * <p>Exact, and it cannot be anything else: both operands are at most two
     * decimal places and a quantity is a whole number, so the product is at most
     * two decimal places as well. Nothing is rounded here because nothing can need
     * to be.
     */
    public BigDecimal lineAmount() {
        return unitAmount.multiply(BigDecimal.valueOf(quantity));
    }
}
