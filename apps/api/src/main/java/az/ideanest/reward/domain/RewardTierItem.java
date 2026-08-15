package az.ideanest.reward.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * How many of one item a tier contains.
 *
 * <p>The composition of a tier, one row per item. Two rows for the same item
 * would be a composition that has to be summed before it can be read, and one of
 * the two would eventually be updated on its own — which is why the pair is the
 * primary key and the count is a column.
 *
 * <p>{@code projectId} is carried rather than derived through either parent. It is
 * what makes both foreign keys composite, and composite foreign keys are what stop
 * a tier from being composed out of another campaign's items — a creator's product
 * appearing inside a stranger's reward. No single-column reference can refuse that,
 * and no application check that could be forgotten should be the only thing that
 * does.
 */
@Entity
@Table(name = "reward_tier_items")
public class RewardTierItem {

    /**
     * The tier and the item, which together identify the row.
     *
     * <p>A class rather than a record because Hibernate instantiates an
     * {@link EmbeddedId} reflectively when it reads a row, and a no-argument
     * constructor is the shape every version of it agrees on.
     */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "reward_tier_id", nullable = false, updatable = false)
        private UUID rewardTierId;

        @Column(name = "item_id", nullable = false, updatable = false)
        private UUID itemId;

        protected Key() {
            // JPA.
        }

        public Key(UUID rewardTierId, UUID itemId) {
            this.rewardTierId = Objects.requireNonNull(rewardTierId, "A composition names its tier");
            this.itemId = Objects.requireNonNull(itemId, "A composition names its item");
        }

        public UUID getRewardTierId() {
            return rewardTierId;
        }

        public UUID getItemId() {
            return itemId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(rewardTierId, key.rewardTierId)
                    && Objects.equals(itemId, key.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rewardTierId, itemId);
        }

        @Override
        public String toString() {
            return "Key[tier=" + rewardTierId + ", item=" + itemId + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected RewardTierItem() {
        // JPA.
    }

    /**
     * @param projectId the campaign both the tier and the item belong to. Passed
     *     rather than looked up because the caller has already established it — and
     *     the composite foreign keys refuse the row if it is wrong, which is
     *     stronger than any argument check here could be
     * @param quantity at least one. Zero of something is not a composition, it is a
     *     row that should have been deleted
     */
    public static RewardTierItem of(UUID projectId, UUID rewardTierId, UUID itemId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("A tier contains at least one of an item it lists");
        }
        RewardTierItem composition = new RewardTierItem();
        composition.id = new Key(rewardTierId, itemId);
        composition.projectId = Objects.requireNonNull(projectId, "A composition belongs to a campaign");
        composition.quantity = quantity;
        return composition;
    }

    public UUID getRewardTierId() {
        return id.getRewardTierId();
    }

    public UUID getItemId() {
        return id.getItemId();
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getQuantity() {
        return quantity;
    }

    /**
     * Changes how many of the item the tier contains.
     *
     * <p>Exists so that replacing a tier's composition can update the row for an
     * item that stayed rather than delete it and insert it again. The
     * delete-and-reinsert would be one statement shorter to write and would have to
     * be flushed between the two halves — Hibernate orders inserts before deletes,
     * so an unflushed pair would try to insert a primary key it has not removed yet.
     */
    public void changeQuantityTo(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("A tier contains at least one of an item it lists");
        }
        this.quantity = quantity;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RewardTierItem composition && Objects.equals(id, composition.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "RewardTierItem[" + id + ", quantity=" + quantity + "]";
    }
}
