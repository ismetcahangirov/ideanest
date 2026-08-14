package az.ideanest.auth.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One opaque refresh token, stored as a hash.
 *
 * <p>The token itself exists once, in the response that issued it. What is kept
 * here is its SHA-256, so a database backup, a log line, or a support query
 * contains nothing anyone can sign in with. No salt and no work factor: the
 * input is 256 bits we generated rather than something a person chose, so there
 * is no dictionary to attack, and this hash is computed on every refresh.
 *
 * <p><strong>A used token is kept, not deleted.</strong> Deleting it would make
 * a replayed token indistinguishable from one that never existed, and that
 * difference is the entire theft signal: an unknown token is noise, a
 * previously rotated token means two parties hold the same credential.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    /** SHA-256, in bytes. The check constraint in the schema says the same thing. */
    public static final int HASH_LENGTH = 32;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
        // JPA.
    }

    private RefreshToken(UUID id, UUID sessionId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash.clone();
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken issue(UUID sessionId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) {
        if (tokenHash.length != HASH_LENGTH) {
            // The database enforces this too. Failing here names the problem;
            // failing there names a constraint.
            throw new IllegalArgumentException(
                    "A refresh token hash must be SHA-256, " + HASH_LENGTH + " bytes, not " + tokenHash.length);
        }
        return new RefreshToken(Identifiers.newIdentifier(), sessionId, tokenHash, issuedAt, expiresAt);
    }

    /**
     * Marks this token as exchanged for {@code replacement}.
     *
     * <p>Rotating twice is not a retry, it is the reuse this design exists to
     * catch, so it fails rather than overwriting the first rotation.
     */
    public void rotateInto(RefreshToken replacement, Instant at) {
        if (this.usedAt != null) {
            throw new IllegalStateException("Refresh token " + id + " was already rotated at " + usedAt);
        }
        this.usedAt = at;
        this.replacedBy = replacement.getId();
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public byte[] getTokenHash() {
        return tokenHash.clone();
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RefreshToken token && Objects.equals(id, token.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // Not even the hash: it is the lookup key, and printing it turns a log
        // into something worth stealing.
        return "RefreshToken[id=" + id + ", sessionId=" + sessionId + ", used=" + isUsed() + "]";
    }
}
