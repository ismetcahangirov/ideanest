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
 * One recovery code, stored as a hash.
 *
 * <p>SHA-256, unsalted, no work factor — the same shape as a refresh token and
 * for the same reason: the input is a hundred bits we generated, so there is no
 * dictionary and no rainbow table to defend against. Argon2 would not only be
 * unnecessary, it would be a liability: this value is checked on an endpoint an
 * attacker can reach with a stolen challenge, and a memory-hard hash there lets
 * them spend nineteen mebibytes of ours per guess.
 *
 * <p>Spent by setting {@code usedAt} rather than by deleting the row, so that
 * "you have already used that one" is distinguishable from "that is not a code"
 * and so that the count of remaining codes stays true.
 */
@Entity
@Table(name = "two_factor_recovery_codes")
public class RecoveryCode {

    public static final int HASH_LENGTH = 32;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private byte[] codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RecoveryCode() {
        // JPA.
    }

    private RecoveryCode(UUID id, UUID userId, byte[] codeHash, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.codeHash = codeHash.clone();
        this.createdAt = createdAt;
    }

    public static RecoveryCode issue(UUID userId, byte[] codeHash, Instant createdAt) {
        if (codeHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "A recovery code hash must be SHA-256, " + HASH_LENGTH + " bytes, not " + codeHash.length);
        }
        return new RecoveryCode(Identifiers.newIdentifier(), userId, codeHash, createdAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getCodeHash() {
        return codeHash.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RecoveryCode code && Objects.equals(id, code.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // Not the hash: it is the lookup key, so printing it turns a log line
        // into a way into somebody's account.
        return "RecoveryCode[id=" + id + ", userId=" + userId + ", used=" + isUsed() + "]";
    }
}
