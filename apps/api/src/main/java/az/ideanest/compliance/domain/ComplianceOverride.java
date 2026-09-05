package az.ideanest.compliance.domain;

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
 * One exception to one compliance requirement for one account — V66's row, issue #436.
 *
 * <p><strong>The only mutable columns are the two that revoke it</strong>, and that is the
 * shape of the table: an override is written once, expires on its own, and is otherwise never
 * edited. {@code FeeSchedule} takes the same position for the same reason — what was true in
 * March has to stay readable — and here there is a second one. An override is the mechanism by
 * which every control in epic #421 can be bypassed by one person in one click, so a row whose
 * reason or expiry could be rewritten afterwards would be a bypass that could also be
 * disguised. Every other column is {@code updatable = false} so a dirty-checked flush cannot
 * emit an UPDATE nobody asked for.
 *
 * <h2>Expiry is a comparison, not a state</h2>
 *
 * <p>There is no {@code EXPIRED} value and no sweep that sets one. {@link #isLiveAt} asks
 * whether the instant is inside the window and the row was not revoked, which means an
 * override stops working because time passed rather than because a job ran. A state column
 * would have made expiry depend on a scheduler, and the scheduler failing would leave every
 * override in the system live.
 *
 * <p>Revocation is the other half, and it is a separate fact rather than an early expiry:
 * "granted until Friday and withdrawn on Wednesday" and "granted until Wednesday" are the
 * same window and different events, and only the first one has somebody's name on the
 * withdrawal.
 */
@Entity
@Table(name = "compliance_overrides")
public class ComplianceOverride {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subject_user_id", nullable = false, updatable = false)
    private UUID subjectUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement", nullable = false, updatable = false)
    private ComplianceRequirement requirement;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private OverrideReason reason;

    @Column(name = "note", nullable = false, updatable = false)
    private String note;

    @Column(name = "granted_by", nullable = false, updatable = false)
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Null until somebody withdraws it. One of the two columns that move. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** The other. Set together with {@link #revokedAt} and never on its own. */
    @Column(name = "revoked_by")
    private UUID revokedBy;

    protected ComplianceOverride() {
        // Hibernate.
    }

    /**
     * Grants an override.
     *
     * <p><strong>The self-grant is refused here and in the database, and the database is the
     * one that counts.</strong> V66's {@code compliance_overrides_grantor_is_not_the_subject}
     * is what makes the rule true against a hand-written INSERT during an incident — which is
     * exactly when somebody would write one, because that is when the only person available to
     * authorise an exception is the person who needs it. This check exists so that the refusal
     * reaches the caller as a sentence rather than as an integrity violation, and it is not
     * the guarantee.
     *
     * @throws SelfGrantedOverrideException when the grantor is the subject
     * @throws IllegalArgumentException when the window is empty or inverted, which is a
     *     programming error rather than an operator's: the controller validates the duration
     *     before it gets here
     */
    public ComplianceOverride(
            UUID id,
            UUID subjectUserId,
            ComplianceRequirement requirement,
            OverrideReason reason,
            String note,
            UUID grantedBy,
            Instant grantedAt,
            Instant expiresAt) {

        this.id = Objects.requireNonNull(id, "id");
        this.subjectUserId = Objects.requireNonNull(subjectUserId, "subjectUserId");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.note = Objects.requireNonNull(note, "note");
        this.grantedBy = Objects.requireNonNull(grantedBy, "grantedBy");
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");

        if (grantedBy.equals(subjectUserId)) {
            throw new SelfGrantedOverrideException(subjectUserId, requirement);
        }
        if (!expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("An override must expire after it is granted.");
        }
    }

    /**
     * Withdraws it, before it would have expired.
     *
     * <p>Idempotent: revoking an already-revoked override keeps the first withdrawal, because
     * the first one is the one that stopped it working and the second changes nothing an
     * auditor would want to read.
     *
     * @return true when this call was the withdrawal
     */
    public boolean revoke(UUID revokedBy, Instant at) {
        if (revokedAt != null) {
            return false;
        }
        this.revokedBy = Objects.requireNonNull(revokedBy, "revokedBy");
        this.revokedAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /**
     * Whether this override is doing anything at that instant.
     *
     * <p>The only question a gate asks. Half-open on the far end — {@code expiresAt} itself is
     * outside — following {@code FeeSchedule.coversInstant}, so that two windows that touch
     * cannot both be in force.
     */
    public boolean isLiveAt(Instant at) {
        return revokedAt == null && !at.isBefore(grantedAt) && at.isBefore(expiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID subjectUserId() {
        return subjectUserId;
    }

    public ComplianceRequirement requirement() {
        return requirement;
    }

    public OverrideReason reason() {
        return reason;
    }

    public String note() {
        return note;
    }

    public UUID grantedBy() {
        return grantedBy;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UUID revokedBy() {
        return revokedBy;
    }
}
