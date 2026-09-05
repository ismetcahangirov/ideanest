package az.ideanest.signature.domain;

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
 * V67's row — a signature the platform has filed against an account, issue #428.
 *
 * <p><strong>Every column is {@code updatable = false}, and there is no setter.</strong> V67 is
 * append-only: a signature is written once and never edited, because an acceptance points at it
 * and a signature whose hash could be rewritten afterwards would make every acceptance of it
 * worthless. That is V65's argument about a published document, one table along.
 *
 * <p><strong>Distinct from {@link StoredSignature}, and the split is deliberate.</strong> This is
 * a row with an identifier and an owning account; that is what a provider returned and what a
 * provider is handed back to verify. An adapter must never see a {@code signer_user_id} — it does
 * not decide whose account this is, #429 does — and a caller of {@code verify} must not have to
 * hold a JPA entity to ask a question about cryptography.
 *
 * <p><strong>Nothing writes one of these yet.</strong> #429 is the caller: it is what has a
 * citizen sign the creator agreement, matches the returned FIN against the account, and binds the
 * row to the acceptance. What #428 ships is the table, the shape, and the constraints — so that
 * the day #429 lands, the thing it writes into already refuses the rows it must refuse.
 */
@Entity
@Table(name = "signatures")
public class SignatureRecord {

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

    @Column(name = "signature_value", nullable = false, updatable = false)
    private String signatureValue;

    @Column(name = "certificate_subject", nullable = false, updatable = false)
    private String certificateSubject;

    @Column(name = "subject_name", nullable = false, updatable = false)
    private String subjectName;

    @Column(name = "subject_fin", nullable = false, updatable = false)
    private String subjectFin;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private Instant signedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected SignatureRecord() {
        // Hibernate.
    }

    /**
     * Files what a provider returned against an account.
     *
     * @param signerUserId whose account this is filed under — <strong>not</strong> a claim that
     *     the certificate belongs to them. #429 is what checks that the subject's FIN matches,
     *     and this constructor deliberately cannot: it has no access to the account
     */
    public SignatureRecord(UUID id, UUID signerUserId, StoredSignature signature) {
        this.id = Objects.requireNonNull(id, "id");
        this.signerUserId = Objects.requireNonNull(signerUserId, "signerUserId");
        Objects.requireNonNull(signature, "signature");
        this.provider = signature.provider();
        this.providerSessionId = signature.providerSessionId();
        this.documentHash = signature.documentHash();
        this.signatureValue = signature.signatureValue();
        this.certificateSubject = signature.subject().distinguishedName();
        this.subjectName = signature.subject().name();
        this.subjectFin = signature.subject().fin();
        this.signedAt = signature.signedAt();
    }

    /** The provider's view of this row, for {@code SignatureProvider.verify}. */
    public StoredSignature asStoredSignature() {
        return new StoredSignature(
                provider,
                providerSessionId,
                documentHash,
                signatureValue,
                new CertificateSubject(certificateSubject, subjectName, subjectFin),
                signedAt);
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

    public Instant signedAt() {
        return signedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
