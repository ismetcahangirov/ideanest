package az.ideanest.reward.application;

import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What shipping a tier costs to one destination, and the arithmetic that turns it
 * into a line on a pledge.
 *
 * <p>{@link BigDecimal} rather than a {@code double}, here as everywhere: this
 * number is added to a pledge total and charged to a card.
 *
 * <p>No currency, because shipping is charged in the campaign's currency and the
 * tier already carries it. See {@code ShippingRule}.
 *
 * <h2>Three numbers, because a carrier tariff has three</h2>
 *
 * <p>§4.8's PM-12 asks for "weight-based <em>or</em> flat rates" and the platform
 * gives both at once, which is what creators are actually reading off when they
 * fill this in: a handling charge for the parcel, a smaller charge for each extra
 * unit in it, and a rate by weight. Setting {@link #perKilogramAmount} to zero is
 * a flat tariff; setting {@link #amount} to zero is a pure weight tariff; setting
 * both is what every real carrier quotes.
 *
 * <p><strong>Where this rate came from is not recorded here.</strong> It may have
 * been a row naming the destination country, or a row pricing a zone the
 * destination falls into — {@code ShippingRates} resolves that and this is the
 * answer. What the record does carry is the country it was resolved <em>for</em>,
 * so that {@code PledgeQuote} can refuse a rate that was fetched for somewhere
 * else.
 *
 * @param countryCode the destination this rate was resolved for, not necessarily
 *     the destination a stored row names
 * @param additionalItemAmount what each unit after the first costs. Zero is
 *     legitimate and is what an omitted value means — a flat rate however many are
 *     ordered is an offer creators make deliberately
 * @param perKilogramAmount what each kilogram costs, added to the flat amounts
 *     rather than replacing them. Zero means this tier is not priced by weight,
 *     which is what almost every campaign means
 */
public record ShippingRate(
        String countryCode,
        BigDecimal amount,
        BigDecimal additionalItemAmount,
        BigDecimal perKilogramAmount) {

    /** A thousand grams. Named because it appears in an expression about money. */
    private static final BigDecimal GRAMS_PER_KILOGRAM = BigDecimal.valueOf(1000);

    /**
     * A tariff with no weight component, which is every rate the platform quoted
     * before #77.
     *
     * <p>Kept as a factory rather than as a compact-constructor default so that the
     * absence of a weight rate is something a caller says, and so the eighty-odd
     * existing call sites in tests read as "flat rate" rather than as "three
     * arguments, one of them zero".
     */
    public static ShippingRate flat(String countryCode, BigDecimal amount, BigDecimal additionalItemAmount) {
        return new ShippingRate(countryCode, amount, additionalItemAmount, BigDecimal.ZERO);
    }

    /**
     * What this rate charges for {@code quantity} units weighing
     * {@code unitWeightGrams} each.
     *
     * <p>The first unit at {@link #amount}, every unit after it at
     * {@link #additionalItemAmount}, plus {@link #perKilogramAmount} for the whole
     * consignment's weight.
     *
     * <h2>This is the one place on the platform that rounds money</h2>
     *
     * <p>Everywhere else the arithmetic is exact by construction — {@link Money}
     * refuses a third decimal place instead of rounding it, and a two-place price
     * times a whole quantity is still two places. Weight breaks that: a rate of
     * 4.50 per kilogram against a parcel of 750 grams is 3.375, and there is no
     * arrangement of the inputs under which it is not.
     *
     * <p>So the rounding is explicit, it happens exactly once — on the weight
     * component alone, before it is added to anything — and it is
     * {@link RoundingMode#HALF_UP}. Half-up rather than up: rounding every parcel
     * up would overcharge every backer by up to a qəpik in the platform's favour,
     * which is not the platform's money to take, and rounding down would do the
     * same to the creator. Half-up is the rule the rest of the world calls
     * commercial rounding and the one a creator checking the sum by hand will
     * reproduce.
     *
     * <p>The alternative — refusing a rate that cannot divide exactly — was
     * rejected: it makes whether a pledge can be placed depend on the weight of
     * what is in it, which is not a rule anybody could explain to a backer at a
     * checkout.
     *
     * @param quantity how many units, at least one
     * @param unitWeightGrams what one unit weighs, or zero when the creator has
     *     recorded no weight. <strong>Zero is not an error.</strong> V7 makes
     *     {@code items.weight_grams} optional, most campaigns never fill it in, and
     *     refusing to quote would turn an incomplete catalogue into a checkout
     *     nobody can complete. The rate editor is where a per-kilogram rate on a
     *     weightless tier is worth warning about
     * @param currency the campaign's, used only to hold every intermediate value to
     *     the same scale {@link Money} holds everything to
     */
    public BigDecimal costFor(int quantity, long unitWeightGrams, String currency) {
        if (quantity < 1) {
            throw new IllegalArgumentException("A shipped line has a quantity of at least 1, not " + quantity);
        }
        if (unitWeightGrams < 0) {
            throw new IllegalArgumentException("A unit cannot weigh less than nothing");
        }

        BigDecimal first = Money.of(amount, currency).amount();
        BigDecimal additional = additionalItemAmount == null
                ? BigDecimal.ZERO.setScale(Money.SCALE)
                : Money.of(additionalItemAmount, currency).amount();
        BigDecimal perKilogram = perKilogramAmount == null
                ? BigDecimal.ZERO.setScale(Money.SCALE)
                : Money.of(perKilogramAmount, currency).amount();

        if (first.signum() < 0 || additional.signum() < 0 || perKilogram.signum() < 0) {
            throw new IllegalArgumentException("Shipping to " + countryCode + " cannot cost less than nothing");
        }

        BigDecimal flat = first.add(additional.multiply(BigDecimal.valueOf(quantity - 1L)));

        if (perKilogram.signum() == 0 || unitWeightGrams == 0) {
            // Nothing to round, and saying so here keeps the exactness of the
            // overwhelmingly common case a property of the code rather than a
            // coincidence of the numbers.
            return flat;
        }

        BigDecimal kilograms = BigDecimal.valueOf(unitWeightGrams)
                .multiply(BigDecimal.valueOf(quantity))
                .divide(GRAMS_PER_KILOGRAM);
        return flat.add(perKilogram.multiply(kilograms).setScale(Money.SCALE, RoundingMode.HALF_UP));
    }

    public ShippingRate {
        Objects.requireNonNull(countryCode, "A rate is resolved for a destination");
        Objects.requireNonNull(amount, "A rate has an amount");
    }
}
