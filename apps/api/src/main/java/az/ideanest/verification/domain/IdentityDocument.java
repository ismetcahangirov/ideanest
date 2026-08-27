package az.ideanest.verification.domain;

import az.ideanest.shared.Identifiers;
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
 * One submitted document, sealed — issue #105.
 *
 * <p><strong>The bytes are never in the clear on this entity.</strong> It holds a
 * ciphertext, a nonce, and the label of the key that sealed it; opening it is
 * {@code DocumentCipher}'s and the plaintext exists only for the length of one request. An
 * entity with a {@code getBytes()} would be one that ends up in a log line, in a heap dump,
 * and in whatever a debugger shows.
 *
 * <p>What is outside the envelope is the metadata a queue needs — kind, media type, size —
 * chosen so that a reviewer can see what arrived, and the retention record can say what was
 * destroyed, without anything being decrypted.
 */
@Entity
@Table(name = "identity_documents")
public class IdentityDocument {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "verification_id", nullable = false, updatable = false)
    private UUID verificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private DocumentKind kind;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "byte_length", nullable = false, updatable = false)
    private int byteLength;

    @Column(name = "ciphertext", nullable = false, updatable = false)
    private byte[] ciphertext;

    @Column(name = "nonce", nullable = false, updatable = false)
    private byte[] nonce;

    @Column(name = "key_id", nullable = false, updatable = false)
    private String keyId;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected IdentityDocument() {
        // JPA.
    }

    private IdentityDocument(
            UUID verificationId,
            DocumentKind kind,
            String contentType,
            int byteLength,
            SealedDocument sealed,
            Instant now) {
        this.id = Identifiers.newIdentifier();
        this.verificationId = Objects.requireNonNull(verificationId, "A document belongs to a verification");
        this.kind = Objects.requireNonNull(kind, "A document is of some kind");
        this.contentType = Objects.requireNonNull(contentType, "A document has a verified media type");
        this.byteLength = byteLength;
        this.ciphertext = sealed.ciphertext();
        this.nonce = sealed.nonce();
        this.keyId = sealed.keyId();
        this.uploadedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public static IdentityDocument of(
            UUID verificationId,
            DocumentKind kind,
            String contentType,
            int byteLength,
            SealedDocument sealed,
            Instant now) {
        return new IdentityDocument(verificationId, kind, contentType, byteLength, sealed, now);
    }

    /** The envelope, for {@code DocumentCipher} and for nothing else. */
    public SealedDocument sealed() {
        return new SealedDocument(ciphertext, nonce, keyId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getVerificationId() {
        return verificationId;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public String getContentType() {
        return contentType;
    }

    public int getByteLength() {
        return byteLength;
    }

    public String getKeyId() {
        return keyId;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    @Override
    public String toString() {
        // No verification, no bytes, no key. This is a row about somebody's passport.
        return "IdentityDocument[id=" + id + ", kind=" + kind + ", bytes=" + byteLength + "]";
    }
}
