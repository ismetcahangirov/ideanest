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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One sign-in, on one device.
 *
 * <p>A session is also the <strong>refresh token family</strong>. Rotation
 * issues a new refresh token into the same session; presenting a token that was
 * already rotated means a copy is in circulation, and the answer is to revoke
 * the session rather than the single token. Revoking the token alone would
 * leave whichever party is holding the newest one signed in — and it is not
 * knowable which of the two that is.
 *
 * <p>Holds {@code userId} as a plain value rather than an association to
 * {@code User}. The two live in different modules, and a JPA association is a
 * compile-time dependency on another module's internals, which is exactly the
 * coupling the boundary exists to prevent. The foreign key is still in the
 * database, because referential integrity is the database's job.
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** What the user sees in the device list. Never trusted for anything else. */
    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "user_agent")
    private String userAgent;

    /**
     * {@code inet}, which validates the value, normalises IPv6, and makes
     * "sessions from this network" a query rather than string comparison.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * When this session proved a second factor, or null for a password alone.
     *
     * <p>A property of the session and not of the user. An account with
     * two-factor switched on can still have sessions that were started before
     * it was, and a payout action asks what this sign-in proved rather than what
     * the account is capable of proving.
     */
    @Column(name = "two_factor_at")
    private Instant twoFactorAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason")
    private SessionRevocationReason revokedReason;

    protected Session() {
        // JPA.
    }

    private Session(UUID id, UUID userId, Instant startedAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.createdAt = startedAt;
        this.lastSeenAt = startedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Every timestamp on a session comes from the application clock, including
     * {@code createdAt}, which the database would otherwise default. Mixing the
     * two would compare an application instant against a database one in the
     * {@code expires_at > created_at} constraint, and a second of clock skew
     * between the two would then reject a legitimate sign-in.
     */
    public static Session start(UUID userId, Instant startedAt, Instant expiresAt) {
        return new Session(Identifiers.newIdentifier(), userId, startedAt, expiresAt);
    }

    public Session describedAs(String deviceLabel, String userAgent, String ipAddress) {
        this.deviceLabel = deviceLabel;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        return this;
    }

    /**
     * Records that a second factor was proved for this session.
     *
     * <p>Set when the session is created from a completed challenge, and when
     * an existing session is the one that confirmed an enrolment — in both cases
     * a code was entered a moment ago, which is the whole claim being made.
     */
    public Session withSecondFactor(Instant at) {
        this.twoFactorAt = at;
        return this;
    }

    /**
     * Whether this sign-in proved a second factor. What a payout action asks.
     */
    public boolean isTwoFactorAuthenticated() {
        return twoFactorAt != null;
    }

    /**
     * Revoking an already revoked session keeps the first reason. Concurrent
     * requests can both detect reuse, and the first detection is the one that
     * describes what happened.
     */
    public void revoke(SessionRevocationReason reason, Instant at) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
            this.revokedReason = reason;
        }
    }

    /** Records activity. Cheap enough to do on refresh, which is the only caller. */
    public void touch(Instant at) {
        this.lastSeenAt = at;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Neither revoked nor expired: the only state in which a refresh may proceed. */
    public boolean isLive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
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

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getTwoFactorAt() {
        return twoFactorAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public SessionRevocationReason getRevokedReason() {
        return revokedReason;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Session session && Objects.equals(id, session.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No IP address and no user agent: §17.4 keeps personal data out of logs.
        return "Session[id=" + id + ", userId=" + userId + ", revoked=" + isRevoked() + "]";
    }
}
