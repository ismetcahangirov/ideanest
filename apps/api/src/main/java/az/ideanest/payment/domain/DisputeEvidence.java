package az.ideanest.payment.domain;

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
 * One piece of a representment — V54, issues #68 and #308.
 *
 * <p>Its own table rather than a {@code jsonb} array on the dispute, for
 * {@code project_faqs}'s reason: each piece is addressed, submitted at its own moment, and
 * carries an acknowledgement of its own. An array index is not an identity.
 *
 * <p>{@code submittedAt} is null while a piece is assembled and not yet sent, which is the
 * state most rows are in while somebody is working a case.
 */
@Entity
@Table(name = "dispute_evidence")
public class DisputeEvidence {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private EvidenceKind kind;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "media_id", updatable = false)
    private UUID mediaId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "provider_evidence_id")
    private String providerEvidenceId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected DisputeEvidence() {
        // Hibernate.
    }

    public DisputeEvidence(UUID disputeId, EvidenceKind kind, String description, UUID mediaId, UUID createdBy) {
        this.id = Identifiers.newIdentifier();
        this.disputeId = Objects.requireNonNull(disputeId, "disputeId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.description = Objects.requireNonNull(description, "description");
        this.mediaId = mediaId;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    /** The provider acknowledged this piece. */
    public void submitted(String providerEvidenceId, Instant at) {
        this.providerEvidenceId = providerEvidenceId;
        this.submittedAt = at;
    }

    public UUID id() {
        return id;
    }

    public UUID disputeId() {
        return disputeId;
    }

    public EvidenceKind kind() {
        return kind;
    }

    public String description() {
        return description;
    }

    public UUID mediaId() {
        return mediaId;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public String providerEvidenceId() {
        return providerEvidenceId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }
}
