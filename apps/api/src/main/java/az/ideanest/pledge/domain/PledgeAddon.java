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
 * One add-on a pledge selected, and how many of it. §4.5's PL-04.
 *
 * <p><strong>Why these are rows and not a number.</strong> §7.2 gives {@code pledges}
 * an {@code addons_amount} and nothing else, and a sum cannot be unpacked: the
 * backer is shown what they selected on every screen after the draft, #56's
 * {@code PATCH} re-quotes a changed selection and cannot re-quote a total, and the
 * creator has to put the right things in the box. V18 creates the table and says so;
 * {@code docs/architecture.md} §7.2 is amended in the same change rather than left
 * describing a schema that is no longer the schema.
 *
 * <p><strong>An entity with its own repository, not a collection on
 * {@link Pledge}.</strong> The same shape as {@code ShippingRule}, and for the same
 * reason: the rows are replaced wholesale when a selection changes, and a mapped
 * collection would have Hibernate deciding when to delete and re-insert them —
 * which, with a composite foreign key into {@code reward_tiers}, is a decision worth
 * making in a statement rather than inheriting.
 *
 * <p><strong>The quantity is not stock.</strong> Nothing here reserves places on the
 * add-on tier; see {@code ReservationService}, which states what that would take and
 * why it is not this issue's.
 */
@Entity
@Table(name = "pledge_addons")
public class PledgeAddon {

    /**
     * The pledge and the tier.
     *
     * <p>One row per tier per pledge, so a quantity cannot be expressed twice and
     * then depend on whoever reads it remembering to add them up. A class rather
     * than a record because JPA requires a no-argument constructor and mutable
     * fields for an {@code @Embeddable} identifier.
     */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "pledge_id", nullable = false, updatable = false)
        private UUID pledgeId;

        @Column(name = "reward_tier_id", nullable = false, updatable = false)
        private UUID rewardTierId;

        protected Key() {
            // JPA.
        }

        public Key(UUID pledgeId, UUID rewardTierId) {
            this.pledgeId = Objects.requireNonNull(pledgeId, "An add-on line belongs to a pledge");
            this.rewardTierId = Objects.requireNonNull(rewardTierId, "An add-on line names a tier");
        }

        public UUID getPledgeId() {
            return pledgeId;
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
                    && Objects.equals(pledgeId, key.pledgeId)
                    && Objects.equals(rewardTierId, key.rewardTierId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pledgeId, rewardTierId);
        }

        @Override
        public String toString() {
            return "Key[pledge=" + pledgeId + ", tier=" + rewardTierId + "]";
        }
    }

    @EmbeddedId
    private Key id;

    /**
     * The campaign, denormalised from both parents.
     *
     * <p>Not a third opinion about which campaign this is: V18's two composite
     * references make it the same {@code project_id} as the pledge's and as the
     * tier's, so a row where they disagreed cannot be written. It is here so that
     * those references can exist at all.
     */
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected PledgeAddon() {
        // JPA.
    }

    public static PledgeAddon of(UUID pledgeId, UUID projectId, UUID rewardTierId, int quantity) {
        if (quantity < 1) {
            // The database refuses the same row. This is the entity holding its own
            // invariant, so a caller that skipped the checkout's validation still
            // cannot write a line for none of something.
            throw new IllegalArgumentException("An add-on line is for at least one, not " + quantity);
        }
        PledgeAddon addon = new PledgeAddon();
        addon.id = new Key(pledgeId, rewardTierId);
        addon.projectId = Objects.requireNonNull(projectId, "An add-on line belongs to a campaign");
        addon.quantity = quantity;
        return addon;
    }

    public UUID getPledgeId() {
        return id.getPledgeId();
    }

    public UUID getRewardTierId() {
        return id.getRewardTierId();
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof PledgeAddon addon && Objects.equals(id, addon.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "PledgeAddon[" + id + ", quantity=" + quantity + "]";
    }
}
