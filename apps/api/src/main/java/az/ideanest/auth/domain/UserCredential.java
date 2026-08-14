package az.ideanest.auth.domain;

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
 * A password credential, one row per user who has one.
 *
 * <p>Separate from {@code User} on purpose. A user who signed in with Google
 * has no password, so the column on {@code users} would be null for a growing
 * share of rows and say nothing about why; and a hash stored beside the profile
 * is loaded into memory by every query that only wanted a display name.
 *
 * <p>The identifier is the user's. There is nothing this row can be without one,
 * and a separate key would only make it possible to have two.
 */
@Entity
@Table(name = "user_credentials")
public class UserCredential {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The full encoded hash, parameters included. Argon2's encoded form carries
     * the memory, time, and parallelism it was produced with, which is what
     * makes raising them a rehash on next sign-in rather than a lockout.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false)
    private PasswordAlgorithm algorithm;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected UserCredential() {
        // JPA.
    }

    private UserCredential(UUID userId, String passwordHash, PasswordAlgorithm algorithm, Instant changedAt) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.algorithm = algorithm;
        this.passwordChangedAt = changedAt;
    }

    public static UserCredential of(UUID userId, String passwordHash, PasswordAlgorithm algorithm, Instant now) {
        return new UserCredential(userId, passwordHash, algorithm, now);
    }

    /**
     * Replaces the hash and records when. Every session issued before this
     * moment is revoked by the caller — a password is changed precisely when
     * somebody believes the old one is known, and leaving those sessions alive
     * makes the change ceremonial.
     */
    public void changePassword(String newHash, PasswordAlgorithm newAlgorithm, Instant changedAt) {
        this.passwordHash = newHash;
        this.algorithm = newAlgorithm;
        this.passwordChangedAt = changedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public PasswordAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
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
        return other instanceof UserCredential credential && Objects.equals(userId, credential.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }

    @Override
    public String toString() {
        // Never the hash. It is not a password, but it is offline-attackable,
        // and a log aggregator is a much easier place to read than a database.
        return "UserCredential[userId=" + userId + ", algorithm=" + algorithm + "]";
    }
}
