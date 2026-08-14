package az.ideanest.auth.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single-use token sent to an email address, for verification or for a
 * password reset.
 *
 * <p>Stored as a SHA-256 hash for the same reason as a refresh token: whoever
 * reads the table must not be able to use what they find. And spent by setting
 * {@code consumedAt} rather than by deleting the row, so that a second attempt
 * with the same link can be told apart from a token that never existed — one is
 * a user double-clicking, the other is somebody guessing.
 */
@Entity
@Table(name = "verification_tokens")
public class VerificationToken {

    public static final int HASH_LENGTH = 32;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private VerificationPurpose purpose;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected VerificationToken() {
        // JPA.
    }

    private VerificationToken(
            UUID id,
            UUID userId,
            VerificationPurpose purpose,
            byte[] tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash.clone();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static VerificationToken issue(
            UUID userId, VerificationPurpose purpose, byte[] tokenHash, Instant createdAt, Instant expiresAt) {
        if (tokenHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "A verification token hash must be SHA-256, " + HASH_LENGTH + " bytes, not " + tokenHash.length);
        }
        return new VerificationToken(Identifiers.newIdentifier(), userId, purpose, tokenHash, createdAt, expiresAt);
    }

    /**
     * Spends the token. Refuses a second time: "single use" that silently
     * tolerates a second use is not single use, and a reset link that keeps
     * working is a standing key to the account.
     */
    public void consume(Instant at) {
        if (this.consumedAt != null) {
            throw new IllegalStateException("Verification token " + id + " was already used at " + consumedAt);
        }
        this.consumedAt = at;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Unspent and unexpired: the only state in which a token may be redeemed. */
    public boolean isRedeemable(Instant now) {
        return !isConsumed() && !isExpired(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public VerificationPurpose getPurpose() {
        return purpose;
    }

    public byte[] getTokenHash() {
        return tokenHash.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof VerificationToken token && Objects.equals(id, token.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "VerificationToken[id=" + id + ", purpose=" + purpose + ", consumed=" + isConsumed() + "]";
    }
}
