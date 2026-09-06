package az.ideanest.signature.domain;

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
 * A signing session, as a row — issue #429. V71's header argues why it is one.
 *
 * <p>Named {@code SignatureSessionRecord} rather than {@code SignatureSession} because #428
 * already has that name for the provider's answer to {@code begin}. The two are different
 * things — one is what SİMA said, the other is what the platform remembers — and giving them
 * the same name is how a later reader concludes there is only one.
 */
@Entity
@Table(name = "signature_sessions")
public class SignatureSessionRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private SignatureProviderName provider;

    @Column(name = "provider_session_id", nullable = false, updatable = false)
    private String providerSessionId;

    @Column(name = "signer_user_id", nullable = false, updatable = false)
    private UUID signerUserId;

    @Column(name = "document_hash", nullable = false, updatable = false)
    private String documentHash;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "purpose", nullable = false, updatable = false)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private SignatureOutcome outcome;

    @Column(name = "signature_id")
    private UUID signatureId;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SignatureSessionRecord() {
    }

    public SignatureSessionRecord(
            UUID id,
            UUID signerUserId,
            SignatureProviderName provider,
            SignatureSession session,
            String documentHash,
            UUID documentId,
            String purpose) {
        this.id = Objects.requireNonNull(id, "id");
        this.signerUserId = Objects.requireNonNull(signerUserId, "signerUserId");
        this.provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(session, "session");
        this.providerSessionId = session.sessionId();
        this.expiresAt = session.expiresAt().truncatedTo(ChronoUnit.MICROS);
        this.documentHash = Objects.requireNonNull(documentHash, "documentHash");
        this.documentId = documentId;
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.outcome = SignatureOutcome.PENDING;
    }

    /** Record what the provider said, once. A terminal session is never re-resolved. */
    public void resolved(SignatureOutcome outcome, UUID signatureId, Instant now) {
        if (this.outcome != SignatureOutcome.PENDING) {
            throw new IllegalStateException("Session " + id + " already resolved to " + this.outcome);
        }
        if ((outcome == SignatureOutcome.SIGNED) != (signatureId != null)) {
            throw new IllegalArgumentException("A signed session carries the signature it produced");
        }
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.signatureId = signatureId;
        this.resolvedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isPending() {
        return outcome == SignatureOutcome.PENDING;
    }

    /**
     * Whether the provider's own deadline has passed.
     *
     * <p>A comparison rather than a swept column, so that a session is expired the moment it is
     * expired rather than the moment something noticed.
     */
    public boolean hasExpiredBy(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean belongsTo(UUID accountId) {
        return signerUserId.equals(accountId);
    }

    public UUID id() {
        return id;
    }

    public UUID signerUserId() {
        return signerUserId;
    }

    public SignatureProviderName provider() {
        return provider;
    }

    public String providerSessionId() {
        return providerSessionId;
    }

    public String documentHash() {
        return documentHash;
    }

    public UUID documentId() {
        return documentId;
    }

    public String purpose() {
        return purpose;
    }

    public SignatureOutcome outcome() {
        return outcome;
    }

    public UUID signatureId() {
        return signatureId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }
}
