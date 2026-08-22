package az.ideanest.pledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a post-campaign purchase — §4.8's PM-10.
 *
 * <p>The same shape as {@link PledgeAddon}, in its own table, and V39 says why it
 * cannot be a row in that one: a backer who bought two mugs during the campaign and
 * one after it would have a single line with a quantity of three and no way to say
 * which part of it {@code pledges.addons_amount} paid for. Either the sum stops
 * matching the lines, or somebody is charged for the same mug twice.
 *
 * <p><strong>What goes in the box is both tables.</strong> A fulfilment reading only
 * {@code pledge_addons} would pack a campaign's add-ons and leave out everything
 * bought in the pledge manager, which is the failure this separation costs and the
 * reason it is stated here, in V39, and in §4.8.
 */
@Entity
@Table(name = "supplement_addons")
public class SupplementAddon {

    /** The supplement and the tier. One row per tier per purchase, like V18's. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "supplement_id", nullable = false, updatable = false)
        private UUID supplementId;

        @Column(name = "reward_tier_id", nullable = false, updatable = false)
        private UUID rewardTierId;

        protected Key() {
            // JPA.
        }

        public Key(UUID supplementId, UUID rewardTierId) {
            this.supplementId = Objects.requireNonNull(supplementId, "A line belongs to a purchase");
            this.rewardTierId = Objects.requireNonNull(rewardTierId, "A line names a tier");
        }

        public UUID getSupplementId() {
            return supplementId;
        }

        public UUID getRewardTierId() {
            return rewardTierId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(supplementId, key.supplementId)
                    && Objects.equals(rewardTierId, key.rewardTierId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(supplementId, rewardTierId);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected SupplementAddon() {
        // JPA.
    }

    public static SupplementAddon of(UUID supplementId, UUID rewardTierId, int quantity) {
        SupplementAddon line = new SupplementAddon();
        line.id = new Key(supplementId, rewardTierId);
        line.quantity = quantity;
        return line;
    }

    public Key getId() {
        return id;
    }

    public UUID getSupplementId() {
        return id.getSupplementId();
    }

    public UUID getRewardTierId() {
        return id.getRewardTierId();
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SupplementAddon line && Objects.equals(id, line.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SupplementAddon[supplement=" + getSupplementId() + ", tier=" + getRewardTierId() + ", quantity="
                + quantity + "]";
    }
}
