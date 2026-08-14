package az.ideanest.auth.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The state between the two halves of a sign-in with two-factor on.
 *
 * <p>A correct password produces one of these and no session. It says one
 * thing: that whoever holds it proved the password for this user, a few minutes
 * ago. It is not a session, it opens no endpoint, and on its own it authorises
 * nothing.
 *
 * <p><strong>A row rather than a signed token.</strong> A JWT would need no
 * table and could not be revoked or spent, and being spendable exactly once is
 * the entire requirement: a challenge that can be replayed is a password-only
 * sign-in with extra steps. Stored as a SHA-256 of a 256-bit value for the same
 * reason a refresh token is — for its short life it is a credential.
 *
 * <p>It carries the device description from the first call so that the session
 * the second call creates describes the device that actually signed in, rather
 * than whatever the second request happened to say.
 */
@Entity
@Table(name = "two_factor_challenges")
public class TwoFactorChallenge {

    public static final int HASH_LENGTH = 32;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "challenge_hash", nullable = false, updatable = false)
    private byte[] challengeHash;

    @Column(name = "device_label", updatable = false)
    private String deviceLabel;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected TwoFactorChallenge() {
        // JPA.
    }

    private TwoFactorChallenge(
            UUID id, UUID userId, byte[] challengeHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.challengeHash = challengeHash.clone();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static TwoFactorChallenge issue(
            UUID userId, byte[] challengeHash, Instant createdAt, Instant expiresAt) {
        if (challengeHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "A challenge hash must be SHA-256, " + HASH_LENGTH + " bytes, not " + challengeHash.length);
        }
        return new TwoFactorChallenge(Identifiers.newIdentifier(), userId, challengeHash, createdAt, expiresAt);
    }

    public TwoFactorChallenge describedAs(String deviceLabel, String userAgent, String ipAddress) {
        this.deviceLabel = deviceLabel;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        return this;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Unspent and unexpired: the only state in which a second factor may be offered against it. */
    public boolean isRedeemable(Instant now) {
        return !isConsumed() && !isExpired(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getChallengeHash() {
        return challengeHash.clone();
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
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
        return other instanceof TwoFactorChallenge challenge && Objects.equals(id, challenge.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No IP address and no user agent: §17.4 keeps personal data out of logs.
        return "TwoFactorChallenge[id=" + id + ", userId=" + userId + ", consumed=" + isConsumed() + "]";
    }
}
