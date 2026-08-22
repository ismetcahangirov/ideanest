package az.ideanest.reward.domain;

import az.ideanest.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * What shipping one tier costs to one country.
 *
 * <p>Per tier rather than per campaign, because the tiers of one campaign differ
 * in size and weight: a poster and a boxed set do not ship for the same money, and
 * a campaign-wide table would price both at whichever the creator entered first.
 *
 * <p><strong>No currency.</strong> Shipping is charged in the campaign's currency,
 * which the tier already carries. A shipping line in a second currency would have
 * to be converted at a rate nobody has chosen, and the backer would be shown a
 * total that does not add up.
 *
 * <p>The set of rules for a tier is replaced wholesale — see
 * {@code PUT /v1/rewards/{id}/shipping-rules}. A rate table is read as a whole by
 * the checkout that quotes from it, and a partial update is how a creator ends up
 * still shipping to a country they believe they removed. What the endpoint cannot
 * change is the destination: {@link #reprice} exists so that a country present in
 * both the old and the new table keeps its row rather than being deleted and
 * inserted again, which Hibernate would order the wrong way round.
 */
@Entity
@Table(name = "shipping_rules")
public class ShippingRule {

    /** The tier and the destination. See {@code RewardTierItem.Key} for why it is a class. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "reward_tier_id", nullable = false, updatable = false)
        private UUID rewardTierId;

        @Column(name = "country_code", nullable = false, updatable = false)
        private String countryCode;

        protected Key() {
            // JPA.
        }

        public Key(UUID rewardTierId, String countryCode) {
            this.rewardTierId = Objects.requireNonNull(rewardTierId, "A shipping rule names its tier");
            this.countryCode = Objects.requireNonNull(countryCode, "A shipping rule names its destination");
        }

        public UUID getRewardTierId() {
            return rewardTierId;
        }

        public String getCountryCode() {
            return countryCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(rewardTierId, key.rewardTierId)
                    && Objects.equals(countryCode, key.countryCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rewardTierId, countryCode);
        }

        @Override
        public String toString() {
            return "Key[tier=" + rewardTierId + ", country=" + countryCode + "]";
        }
    }

    /** ISO 3166-1 alpha-2, uppercase. The database checks the same shape. */
    private static final int COUNTRY_CODE_LENGTH = 2;

    @EmbeddedId
    private Key id;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /**
     * What each unit after the first costs. Zero is a legitimate answer and the
     * default: "one flat rate however many you order" is an offer creators make
     * deliberately, and it is better said by a zero the creator can see than by a
     * null the calculation has to interpret.
     */
    @Column(name = "additional_item_amount", nullable = false)
    private BigDecimal additionalItemAmount;

    /**
     * §4.8's PM-12, added by V37: what each kilogram costs, <strong>added to</strong>
     * {@link #amount} rather than replacing it, because that is the shape every
     * carrier tariff has — a handling charge plus a rate by weight.
     *
     * <p>Zero is the default and means "this tier is not priced by weight", which is
     * what almost every campaign means. The weight itself is summed from
     * {@code items.weight_grams} over the tier's contents; V7 put that column on the
     * item and said a tier's weight is a query rather than a column somebody
     * maintains, and #77 is where that query is finally run.
     */
    @Column(name = "per_kilogram_amount", nullable = false)
    private BigDecimal perKilogramAmount;

    protected ShippingRule() {
        // JPA.
    }

    /**
     * @param countryCode normalised to uppercase here, because a creator typing
     *     "az" means Azerbaijan and a rate table with both "az" and "AZ" in it is a
     *     table with two answers for one destination
     * @throws IllegalArgumentException when the code is not two letters or a rate
     *     is negative. A negative rate would be a discount applied through the
     *     shipping line, which is not what this is and makes the pledge total
     *     unexplainable
     */
    public static ShippingRule of(
            UUID rewardTierId,
            String countryCode,
            BigDecimal amount,
            BigDecimal additionalItemAmount,
            BigDecimal perKilogramAmount) {

        String normalised = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        if (normalised.length() != COUNTRY_CODE_LENGTH || !normalised.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("A destination is a two-letter ISO 3166-1 country code");
        }
        ShippingRule rule = new ShippingRule();
        rule.id = new Key(rewardTierId, normalised);
        rule.amount = exact(amount, "A shipping rate");
        rule.additionalItemAmount = exact(additionalItemAmount, "An additional-item rate");
        rule.perKilogramAmount = exact(perKilogramAmount, "A per-kilogram rate");
        return rule;
    }

    /**
     * The rate as {@code numeric(14,2)} holds it: not negative, at most two decimal
     * places, and padded to two.
     *
     * <p>The same discipline as {@link az.ideanest.shared.money.Money}, and for the same
     * reasons. A third decimal place is refused rather than rounded, because PostgreSQL
     * would round it silently and a shipping line that quietly gains a qəpik is a pledge
     * total that does not add up. The padding is what makes the response say
     * {@code "5.00"} for a rate entered as {@code 5}, so a client comparing what it sent
     * with what it got back is not told the value changed.
     *
     * <p>Not a {@code Money}, because a rate carries no currency of its own — it is
     * denominated in the campaign's, which the tier holds. Sharing the scale constant is
     * as much as the two have in common.
     */
    private static BigDecimal exact(BigDecimal value, String what) {
        Objects.requireNonNull(value, what + " is required");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(what + " cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalArgumentException(
                    what + " has at most " + Money.SCALE + " decimal places");
        }
        return value.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
    }

    public UUID getRewardTierId() {
        return id.getRewardTierId();
    }

    public String getCountryCode() {
        return id.getCountryCode();
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAdditionalItemAmount() {
        return additionalItemAmount;
    }

    public BigDecimal getPerKilogramAmount() {
        return perKilogramAmount;
    }

    /** The same destination at new rates. See the class comment for why this exists. */
    public void reprice(BigDecimal amount, BigDecimal additionalItemAmount, BigDecimal perKilogramAmount) {
        this.amount = exact(amount, "A shipping rate");
        this.additionalItemAmount = exact(additionalItemAmount, "An additional-item rate");
        this.perKilogramAmount = exact(perKilogramAmount, "A per-kilogram rate");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ShippingRule rule && Objects.equals(id, rule.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ShippingRule[" + id + "]";
    }
}
