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
import java.util.Objects;
import java.util.UUID;

/**
 * What shipping one tier to one zone costs — §4.8's PM-12 and PM-13.
 *
 * <p>The same three amounts {@link ShippingRule} carries, for a group of
 * destinations instead of a named one. Per tier, and for {@link ShippingRule}'s
 * reason: the tiers of one campaign differ in size and weight, so a campaign-wide
 * table would price a poster and a boxed set at whichever the creator entered
 * first.
 *
 * <p><strong>A named-country rule always beats this.</strong> A creator who prices
 * the EU at 12 and then writes a row for Germany at 8 has said something specific
 * about Germany, and the only reading of the second row under which it means
 * anything is that it overrides the first. See {@code ShippingRates}, which is
 * where the precedence lives and where it is asserted.
 *
 * <p>No currency, for {@link ShippingRule}'s reason: shipping is charged in the
 * campaign's currency, which the tier carries.
 */
@Entity
@Table(name = "shipping_zone_rules")
public class ShippingZoneRule {

    /** The tier and the zone. See {@code ShippingRule.Key} for why it is a class. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "reward_tier_id", nullable = false, updatable = false)
        private UUID rewardTierId;

        @Column(name = "zone_id", nullable = false, updatable = false)
        private UUID zoneId;

        protected Key() {
            // JPA.
        }

        public Key(UUID rewardTierId, UUID zoneId) {
            this.rewardTierId = Objects.requireNonNull(rewardTierId, "A zone rate names its tier");
            this.zoneId = Objects.requireNonNull(zoneId, "A zone rate names its zone");
        }

        public UUID getRewardTierId() {
            return rewardTierId;
        }

        public UUID getZoneId() {
            return zoneId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(rewardTierId, key.rewardTierId)
                    && Objects.equals(zoneId, key.zoneId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rewardTierId, zoneId);
        }

        @Override
        public String toString() {
            return "Key[tier=" + rewardTierId + ", zone=" + zoneId + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "additional_item_amount", nullable = false)
    private BigDecimal additionalItemAmount;

    /**
     * PM-12's weight half, added to the flat amounts rather than replacing them.
     * Zero means this tier is not priced by weight, which is what almost every
     * campaign means.
     */
    @Column(name = "per_kilogram_amount", nullable = false)
    private BigDecimal perKilogramAmount;

    protected ShippingZoneRule() {
        // JPA.
    }

    /**
     * @throws IllegalArgumentException when a rate is negative or carries more than
     *     two decimal places. Refused rather than rounded, exactly as
     *     {@link ShippingRule} refuses it and for the same reason: PostgreSQL would
     *     round a third place silently, and a shipping line that quietly gains a
     *     qəpik is a pledge total that does not add up
     */
    public static ShippingZoneRule of(
            UUID rewardTierId,
            UUID zoneId,
            UUID projectId,
            BigDecimal amount,
            BigDecimal additionalItemAmount,
            BigDecimal perKilogramAmount) {

        ShippingZoneRule rule = new ShippingZoneRule();
        rule.id = new Key(rewardTierId, zoneId);
        rule.projectId = Objects.requireNonNull(projectId, "A zone rate belongs to a campaign");
        rule.amount = exact(amount, "A shipping rate");
        rule.additionalItemAmount = exact(additionalItemAmount, "An additional-item rate");
        rule.perKilogramAmount = exact(perKilogramAmount, "A per-kilogram rate");
        return rule;
    }

    /**
     * The rate as {@code numeric(14,2)} holds it: not negative, at most two decimal
     * places, and padded to two.
     *
     * <p>Duplicated from {@link ShippingRule} rather than shared, and the
     * duplication is the smaller cost: extracting it would mean a helper in the
     * domain package whose only job is to hold three lines, and the two entities
     * would then be coupled through it for no gain either side asked for.
     */
    private static BigDecimal exact(BigDecimal value, String what) {
        Objects.requireNonNull(value, what + " is required");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(what + " cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalArgumentException(what + " has at most " + Money.SCALE + " decimal places");
        }
        return value.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
    }

    public UUID getRewardTierId() {
        return id.getRewardTierId();
    }

    public UUID getZoneId() {
        return id.getZoneId();
    }

    public UUID getProjectId() {
        return projectId;
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

    /** The same tier and zone at new rates. See {@code ShippingRule.reprice} for why this exists. */
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
        return other instanceof ShippingZoneRule rule && Objects.equals(id, rule.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ShippingZoneRule[" + id + "]";
    }
}
