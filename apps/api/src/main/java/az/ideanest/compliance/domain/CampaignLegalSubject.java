package az.ideanest.compliance.domain;

import az.ideanest.shared.compliance.LegalSubject;
import az.ideanest.shared.compliance.SubjectKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Who the creator was, legally, when this campaign was submitted — issue #430.
 *
 * <p><strong>A copy, on purpose.</strong> §5.6 snapshots a subscription's price, V42 freezes a
 * retry window, and {@code payouts.creator_id} is denormalised from the campaign because "a
 * campaign's creator is a mutable fact and the payout was calculated for the person who held
 * it at the time". A legal subject is mutable in the same way and is read at the same moments,
 * so it is frozen for the same reason.
 *
 * <p>What the freeze buys is a specific test: a creator edits their subject after submitting,
 * and the submitted campaign does not change. Without it, a campaign submitted by an
 * individual could be paid out as though a company had submitted it, with different
 * withholding, on the strength of an edit made after every decision about that campaign had
 * been taken.
 *
 * <p>Keyed by the campaign rather than by (campaign, submission). A resubmission after a
 * rejection overwrites: that submission is the one being decided, and a history of subjects a
 * campaign was submitted under is a question nobody has asked. What it was submitted under
 * <em>last</em> is what every reader wants, and {@code audit_logs} carries the changes.
 */
@Entity
@Table(name = "campaign_legal_subjects")
public class CampaignLegalSubject {

    @Id
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_kind", nullable = false)
    private SubjectKind subjectKind;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "registered_address")
    private String registeredAddress;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;

    protected CampaignLegalSubject() {
    }

    private CampaignLegalSubject(UUID projectId, UUID userId, LegalSubject subject, Instant now) {
        this.projectId = Objects.requireNonNull(projectId, "A snapshot is of a campaign");
        // Not the public `freeze`, for `CreatorLegalSubject`'s reason: `-Xlint:this-escape`
        // is an error here.
        overwrite(userId, subject, now);
    }

    public static CampaignLegalSubject of(UUID projectId, UUID userId, LegalSubject subject, Instant now) {
        return new CampaignLegalSubject(projectId, userId, subject, now);
    }

    /** Overwrite with what is true now — a resubmission. */
    public void freeze(UUID userId, LegalSubject subject, Instant now) {
        overwrite(userId, subject, now);
    }

    private void overwrite(UUID userId, LegalSubject subject, Instant now) {
        Objects.requireNonNull(subject, "A snapshot is of a subject");
        this.userId = Objects.requireNonNull(userId, "A snapshot is of somebody");
        this.subjectKind = subject.subjectKind();
        this.legalName = subject.legalName();
        this.taxId = subject.taxIdentifier().orElse(null);
        this.registeredAddress = subject.address().orElse(null);
        this.registrationNumber = subject.registration().orElse(null);
        this.frozenAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public LegalSubject asLegalSubject() {
        return new LegalSubject(subjectKind, legalName, taxId, registeredAddress, registrationNumber);
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }
}
