package az.ideanest.analytics.domain;

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
 * One visit that carried a source. The evidence attribution is decided from.
 *
 * <p><strong>The window is on the row.</strong> {@link #getExpiresAt()} is computed
 * once, when the visit is recorded, from the attribution window in force at that
 * moment — never from configuration at the time a report is read. A window applied at
 * read time would mean shortening it silently deleted history and lengthening it
 * silently invented some, and neither change would appear anywhere a creator could
 * see it. V24's header makes the same argument against the schema's alternative.
 *
 * <p><strong>Almost everything here is immutable, and the one mutable thing is the
 * point.</strong> {@link #claimedBy(UUID)} is the only state change: an anonymous
 * visit becoming a visit by a known account, which is what happens the moment a
 * visitor signs in holding the token they were already carrying. Nothing else about a
 * recorded visit can change — a source that could be edited afterwards is not
 * evidence.
 *
 * <p>No identity of the visitor is stored beyond {@link #getVisitorHash()}, which is
 * the SHA-256 of randomness; see {@link VisitorToken} for why it is randomness and not
 * something derived.
 */
@Entity
@Table(name = "referral_touches")
public class ReferralTouch {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "visitor_hash", nullable = false, updatable = false)
    private byte[] visitorHash;

    /** Null for the anonymous part of the journey, which is most of it. */
    @Column(name = "backer_id")
    private UUID backerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private ReferralChannel channel;

    @Column(name = "source", updatable = false)
    private String source;

    @Column(name = "campaign", updatable = false)
    private String campaign;

    @Column(name = "referrer_code", updatable = false)
    private String referrerCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected ReferralTouch() {
        // JPA.
    }

    /**
     * A visit, as it happened.
     *
     * <p>Public rather than package private, unlike {@code AuditEntry.record}: there
     * is nothing to redact or bound here that {@link ReferralSource} has not already
     * bounded, and the alternative — a factory reachable only from the service —
     * would make the attribution rule untestable without a database, which is the one
     * thing about this feature that most needs testing without one.
     *
     * @param visitorHash the SHA-256 of the visitor's token. Copied on the way in, so
     *     that a caller reusing its buffer cannot change a row that has been recorded
     * @param backerId the account, when the visit was made by somebody signed in.
     *     Null otherwise, and filled in later by {@link #claimedBy(UUID)}
     * @param expiresAt the end of the attribution window for this visit. Passed in
     *     rather than computed here, because the window is configuration and the
     *     domain does not read configuration
     */
    public static ReferralTouch record(
            UUID projectId,
            byte[] visitorHash,
            UUID backerId,
            ReferralSource source,
            Instant occurredAt,
            Instant expiresAt) {

        ReferralTouch touch = new ReferralTouch();
        touch.id = Identifiers.newIdentifier();
        touch.projectId = Objects.requireNonNull(projectId, "A visit is a visit to a campaign");
        touch.visitorHash =
                Objects.requireNonNull(visitorHash, "A visit is remembered by a visitor").clone();
        touch.backerId = backerId;

        Objects.requireNonNull(source, "A visit says where it came from, even if the answer is DIRECT");
        touch.channel = source.channel();
        touch.source = source.source();
        touch.campaign = source.campaign();
        touch.referrerCode = source.referrerCode();

        touch.occurredAt = Objects.requireNonNull(occurredAt, "A visit happened at some moment");
        touch.expiresAt = Objects.requireNonNull(expiresAt, "A visit stops being evidence at some moment");
        if (!expiresAt.isAfter(occurredAt)) {
            // Mirrored by referral_touches_window_is_open. Refused here as well so
            // that a misconfigured window fails at the line that computed it rather
            // than at a constraint whose name names a table.
            throw new IllegalArgumentException("An attribution window closes after it opens");
        }
        return touch;
    }

    /**
     * Attaches this visit to the account the visitor turned out to be.
     *
     * <p>Called when somebody signed in presents a token they were already holding —
     * the browsing they did before they had an account, becoming attributable to the
     * pledge they are about to make. Without it the whole pre-sign-in journey is
     * invisible to the rule, and every campaign would appear to convert only the
     * people who arrived already logged in.
     *
     * <p>Idempotent, and it does not re-point a touch that is already claimed. Two
     * accounts presenting one token is a shared device or a copied link, and moving
     * the row would silently transfer one person's browsing to another's report.
     */
    public void claimedBy(UUID accountId) {
        Objects.requireNonNull(accountId, "A visit is claimed by an account");
        if (backerId == null) {
            backerId = accountId;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    /** A copy: the array is the row's, and a caller that could mutate it could rewrite history. */
    public byte[] getVisitorHash() {
        return visitorHash.clone();
    }

    public UUID getBackerId() {
        return backerId;
    }

    /** The channel and its labels, reassembled — the same value the visit was recorded with. */
    public ReferralSource getSource() {
        return new ReferralSource(channel, source, campaign, referrerCode);
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ReferralTouch touch && Objects.equals(id, touch.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No visitor hash and no account. This ends up in log lines, and §17.4's rules
        // about what a log line may carry are not relaxed by the value being a hash.
        return "ReferralTouch[id=" + id + ", projectId=" + projectId + ", channel=" + channel
                + ", occurredAt=" + occurredAt + "]";
    }
}
