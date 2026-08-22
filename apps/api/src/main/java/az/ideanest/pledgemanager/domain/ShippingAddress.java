package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pledge's postal address, as it is stored: a ciphertext, a nonce, and the label
 * of the key that made them.
 *
 * <p><strong>This class never sees a street.</strong> {@code PostalAddress} is the
 * readable form and {@code AddressCipher} is the only thing that converts between the
 * two, so nothing in the persistence layer holds a decrypted address and nothing that
 * reads this entity can accidentally log one. That is the property V36 exists for and
 * it is cheap to keep only if it is kept everywhere.
 *
 * <p><strong>Per pledge, not per account.</strong> A backer who moves house between
 * two campaigns has two addresses, and the earlier campaign ships to where they lived
 * when they answered. An address on the account would silently rewrite an answer a
 * creator has already printed a label from.
 *
 * <h2>The lock</h2>
 *
 * <p>§4.8's PM-08. {@code lockedAt} null means the backer may still edit; a creator
 * sets it when they start manufacturing. It is per address rather than a flag on the
 * campaign so that a creator can reopen one backer who wrote in — which is the whole
 * of what makes the feature usable rather than a wall.
 */
@Entity
@Table(name = "shipping_addresses")
public class ShippingAddress {

    /** AES-GCM's specified nonce length. V36 checks the same number. */
    public static final int NONCE_LENGTH = 12;

    @Id
    @Column(name = "pledge_id", nullable = false, updatable = false)
    private UUID pledgeId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "backer_id", nullable = false, updatable = false)
    private UUID backerId;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @Column(name = "nonce", nullable = false)
    private byte[] nonce;

    @Column(name = "key_id", nullable = false)
    private String keyId;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private UUID lockedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ShippingAddress() {
        // JPA.
    }

    /** The first time a backer answers. */
    public static ShippingAddress of(UUID pledgeId, UUID projectId, UUID backerId, SealedAddress sealed) {
        ShippingAddress address = new ShippingAddress();
        address.pledgeId = Objects.requireNonNull(pledgeId, "An address belongs to a pledge");
        address.projectId = Objects.requireNonNull(projectId, "An address belongs to a campaign");
        address.backerId = Objects.requireNonNull(backerId, "An address was given by somebody");
        address.replaceWith(sealed);
        return address;
    }

    /**
     * A new answer to the same question.
     *
     * <p>Everything in the envelope is replaced together, including the key label: an
     * address rewritten after a key rotation is re-encrypted under the current key,
     * which is what makes rotation a gradual migration rather than one transaction
     * over the whole table.
     */
    public void replaceWith(SealedAddress sealed) {
        Objects.requireNonNull(sealed, "There is nothing to store");
        if (sealed.nonce().length != NONCE_LENGTH) {
            throw new IllegalArgumentException("An AES-GCM nonce is " + NONCE_LENGTH + " bytes");
        }
        if (sealed.ciphertext().length == 0) {
            throw new IllegalArgumentException("An encryption that produced nothing is not an address");
        }
        this.ciphertext = sealed.ciphertext().clone();
        this.nonce = sealed.nonce().clone();
        this.keyId = Objects.requireNonNull(sealed.keyId(), "A stored address records which key sealed it");
    }

    /** PM-08: the creator freezes this address so they can print a label from it. */
    public void lock(UUID by, Instant at) {
        this.lockedBy = Objects.requireNonNull(by, "A lock records who applied it");
        this.lockedAt = Objects.requireNonNull(at, "A lock records when it was applied");
    }

    /** The creator reopening one backer. Both columns clear together, as V36 requires. */
    public void unlock() {
        this.lockedBy = null;
        this.lockedAt = null;
    }

    public boolean isLocked() {
        return lockedAt != null;
    }

    public UUID getPledgeId() {
        return pledgeId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getBackerId() {
        return backerId;
    }

    /** Cloned, so a caller cannot mutate the entity's array through the reference. */
    public SealedAddress getSealed() {
        return new SealedAddress(ciphertext.clone(), nonce.clone(), keyId);
    }

    public String getKeyId() {
        return keyId;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public UUID getLockedBy() {
        return lockedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ShippingAddress address && Objects.equals(pledgeId, address.pledgeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pledgeId);
    }

    @Override
    public String toString() {
        // Identifiers and the key label only. See PostalAddress.toString.
        return "ShippingAddress[pledge=" + pledgeId + ", key=" + keyId + ", locked=" + isLocked() + "]";
    }
}
