package az.ideanest.verification.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One creator's identity check — issue #105.
 *
 * <p>A current state, not a history: there is one row per person and it is replaced in
 * place. The history of decisions is {@code audit_logs}, which is the table with a
 * retention policy somebody has argued for, and duplicating it here would be a second
 * record of who was refused with no rule about how long it is kept.
 *
 * <p><strong>Nothing on the platform is gated on this.</strong> §22.1 makes the threshold
 * a legal question (#71, {@code needs-decision}), so a campaign launches, a pledge is
 * taken and a payout is calculated exactly as before whatever this says. The state
 * machine is here so that the day the answer arrives is a wiring change.
 */
@Entity
@Table(name = "identity_verifications")
public class IdentityVerification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private VerificationState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_kind", nullable = false)
    private SubjectKind subjectKind;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason")
    private RejectionReason rejectionReason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "documents_erased_at")
    private Instant documentsErasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdentityVerification() {
        // JPA.
    }

    private IdentityVerification(UUID userId, SubjectKind subjectKind, Instant now) {
        this.id = Identifiers.newIdentifier();
        this.userId = Objects.requireNonNull(userId, "A verification is somebody's");
        this.subjectKind = Objects.requireNonNull(subjectKind, "A verification is of a person or a company");
        this.state = VerificationState.REQUESTED;
        this.createdAt = at(now);
        this.updatedAt = at(now);
    }

    /** A verification that has been asked for and not yet submitted to. */
    public static IdentityVerification requested(UUID userId, SubjectKind subjectKind, Instant now) {
        return new IdentityVerification(userId, subjectKind, now);
    }

    /**
     * Documents have arrived.
     *
     * <p>Callable from {@code REQUESTED} and from {@code REJECTED} — a rejection for an
     * unreadable photograph is a photograph taken again — and from {@code EXPIRED}, which
     * is a check that has aged. Not from {@code SUBMITTED}: adding a document to a
     * submission somebody may be halfway through reading is how a reviewer approves a set
     * they did not see.
     */
    public void submitted(SubjectKind kind, Instant now) {
        if (state == VerificationState.SUBMITTED) {
            throw new IllegalStateException("This verification is already waiting to be reviewed");
        }
        if (state == VerificationState.APPROVED) {
            throw new IllegalStateException("This verification has already been approved");
        }
        this.subjectKind = Objects.requireNonNull(kind, "A verification is of a person or a company");
        this.state = VerificationState.SUBMITTED;
        // Cleared, because they belonged to the previous decision. A rejection reason left
        // on a resubmission is a creator still being shown why last month's photograph was
        // refused.
        this.rejectionReason = null;
        this.reviewedBy = null;
        this.reviewedAt = null;
        this.updatedAt = at(now);
    }

    /**
     * A member of staff was satisfied.
     *
     * @param life how long the approval counts for. Stored as an instant rather than
     *     recomputed, so a verification keeps the rule it was approved under when the
     *     configured life changes
     */
    public void approved(UUID staffId, Duration life, Instant now) {
        requireSubmitted();
        this.state = VerificationState.APPROVED;
        this.reviewedBy = Objects.requireNonNull(staffId, "A decision has somebody behind it");
        this.reviewedAt = at(now);
        this.rejectionReason = null;
        this.expiresAt = at(now.plus(life));
        this.updatedAt = at(now);
    }

    /** A member of staff was not satisfied. */
    public void rejected(UUID staffId, RejectionReason reason, Instant now) {
        requireSubmitted();
        this.state = VerificationState.REJECTED;
        this.reviewedBy = Objects.requireNonNull(staffId, "A decision has somebody behind it");
        this.reviewedAt = at(now);
        this.rejectionReason = Objects.requireNonNull(reason, "A refusal says why");
        this.expiresAt = null;
        this.updatedAt = at(now);
    }

    /**
     * An approval that has aged out.
     *
     * <p>Moved by a sweep rather than computed on read, so that the state in the database
     * is the state the platform will act on. A verification that read as approved to one
     * query and expired to another would be the worst of both.
     */
    public void expired(Instant now) {
        if (state != VerificationState.APPROVED) {
            throw new IllegalStateException("Only an approval expires");
        }
        this.state = VerificationState.EXPIRED;
        this.updatedAt = at(now);
    }

    /**
     * Records that the documents behind this decision have been destroyed.
     *
     * <p>Idempotent: the sweep runs daily and a verification whose documents went last week
     * must not have its erasure date moved to today. The date is the answer to "when did we
     * stop holding this person's passport", and moving it would make it a lie.
     */
    public void documentsErased(Instant now) {
        if (documentsErasedAt == null) {
            this.documentsErasedAt = at(now);
            this.updatedAt = at(now);
        }
    }

    private void requireSubmitted() {
        if (state != VerificationState.SUBMITTED) {
            throw new IllegalStateException("Only a submitted verification can be decided");
        }
    }

    /**
     * The instant at the precision {@code timestamptz} holds.
     *
     * <p>Without it the entity in memory and the row disagree in the last three digits, so
     * a response written from the entity does not match the one written from a later read.
     */
    private static Instant at(Instant now) {
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public VerificationState getState() {
        return state;
    }

    public SubjectKind getSubjectKind() {
        return subjectKind;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDocumentsErasedAt() {
        return documentsErasedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        // No user. Whether somebody is being identity-checked is a fact about them (§17.4)
        // and this ends up in log lines.
        return "IdentityVerification[id=" + id + ", state=" + state + "]";
    }
}
