package az.ideanest.auth.domain;

import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An address change that has been asked for and not yet proven — §4.1's A-12.
 *
 * <p><strong>The account's address does not move until this row is spent.</strong>
 * V44 carries the argument: writing the new address to {@code users} and clearing
 * the verified flag would mean one typo puts the account behind a mailbox nobody
 * can read — sign-in is by address, and so is the reset that would fix it.
 *
 * <p>Shaped like {@link VerificationToken} and deliberately not one of them. That
 * row is (user, purpose, hash, expiry) and has nowhere to put the address being
 * proven; a nullable payload column there would be null on every other row and
 * would put an email address in a table §17.4 sweeps by hash.
 */
@Entity
@Table(name = "email_change_requests")
public class EmailChangeRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The address being proven, normalised by {@link EmailAddress} and stored in a
     * {@code citext} column so that this table and {@code users.email} agree about
     * what a duplicate is.
     */
    @Column(name = "new_email", nullable = false, updatable = false, columnDefinition = "citext")
    private EmailAddress newEmail;

    /** SHA-256 of the emailed value. The value itself exists only in the message. */
    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected EmailChangeRequest() {
        // JPA.
    }

    private EmailChangeRequest(UUID id, UUID userId, EmailAddress newEmail, byte[] tokenHash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.newEmail = newEmail;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static EmailChangeRequest issue(UUID userId, EmailAddress newEmail, byte[] tokenHash, Instant expiresAt) {
        return new EmailChangeRequest(Identifiers.newIdentifier(), userId, newEmail, tokenHash, expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public EmailAddress getNewEmail() {
        return newEmail;
    }

    public byte[] getTokenHash() {
        return tokenHash;
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
        return other instanceof EmailChangeRequest request && Objects.equals(id, request.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No address and no hash. This lands in logs, where §17.4 redacts the
        // first and where the second is a credential against the account.
        return "EmailChangeRequest[id=" + id + ", userId=" + userId + ", consumed=" + isConsumed() + "]";
    }
}
