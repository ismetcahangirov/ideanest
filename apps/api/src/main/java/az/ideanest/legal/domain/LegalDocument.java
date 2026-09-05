package az.ideanest.legal.domain;

import az.ideanest.shared.ReaderLocale;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * One version of one of §22.2's documents, in one language — V65's row, issue #425.
 *
 * <h2>A draft is editable; a published version is not</h2>
 *
 * <p>{@link #publishedAt} is the whole state machine. Before it is set, the title and body
 * are freely rewritten — that is what the console's editor writes into. After it is set,
 * nothing changes, ever, and V65's {@code legal_documents_published_is_immutable} trigger
 * refuses the UPDATE rather than trusting this class to be the only writer.
 *
 * <p>The reason is what an acceptance is. {@code document_acceptances} names a version, and
 * an acceptance of a text that can be edited afterwards is evidence of nothing. So a
 * correction is a new version, which is more ceremony than a typo deserves and exactly the
 * right amount for the case the ceremony is for.
 *
 * <p>The mapping enforces it from the Java side as well: every column but {@code publishedAt},
 * {@code publishedBy} and {@code effectiveFrom} is {@code updatable = false} on a published
 * row — which cannot be expressed per-state in JPA, so the guard is {@link #requireDraft}
 * on every mutator and the trigger underneath it.
 *
 * <h2>The hash is of the body, and it is computed here</h2>
 *
 * <p>Because #429 will hand a hash to SİMA and the acceptance record has to be able to
 * prove which text was signed. Computed on write rather than on read, so that a body which
 * no longer hashes to the stored value is a body somebody edited — which is a thing a test
 * can assert and a read-side digest could not.
 *
 * <p>SHA-256, lower-case hex, over the UTF-8 bytes of the body exactly as stored. Every
 * part of that sentence has to stay true on both sides of a signature, so it is stated
 * once, here, and V65's {@code legal_documents_content_hash_shape} refuses anything that is
 * not a digest.
 */
@Entity
@Table(name = "legal_documents")
public class LegalDocument {

    /** V65's {@code legal_documents_title_present}, stated once more where it is applied. */
    private static final int TITLE_MAX = 200;

    /** V65's {@code legal_documents_body_present}. A megabyte of terms is a paste accident. */
    private static final int BODY_MAX = 1_048_576;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private DocumentKind kind;

    @Column(name = "locale", nullable = false, updatable = false)
    private String locale;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LegalDocument() {
        // JPA.
    }

    /**
     * A new draft of the next version of a document in one language.
     *
     * <p>The version is allocated by the caller, which is {@code LegalDocuments} — and it is
     * allocated <strong>per kind</strong> rather than per (kind, locale), so that version 4
     * of the creator agreement is version 4 in all four languages. V65's unique constraint
     * is still per (kind, locale) and is still satisfied; what the allocation adds is that
     * an acceptance naming a version identifies one agreement rather than one translation of
     * an unknown one.
     */
    public static LegalDocument draft(
            UUID id, DocumentKind kind, String locale, int version, String title, String body, UUID author, Instant now) {

        LegalDocument document = new LegalDocument();
        document.id = Objects.requireNonNull(id, "id");
        document.kind = Objects.requireNonNull(kind, "kind");
        document.locale = requireSupported(locale);
        if (version < 1) {
            throw new IllegalArgumentException("A version starts at 1; got " + version);
        }
        document.version = version;
        document.createdAt = Objects.requireNonNull(now, "now");
        document.createdBy = author;
        document.updatedAt = now;
        document.rewrite(title, body, now);
        return document;
    }

    /**
     * Replaces the text of a draft.
     *
     * <p>The hash moves with the body, in one method, because a body and a hash that
     * disagree are worse than either alone: the disagreement is only discovered by whoever
     * checks a signature, years later, and their conclusion is that the platform edited a
     * signed document.
     *
     * @throws PublishedDocumentIsImmutableException when this version has been published
     */
    public void rewrite(String title, String body, Instant now) {
        requireDraft();
        this.title = requireText(title, TITLE_MAX, "title");
        this.body = requireText(body, BODY_MAX, "body");
        this.contentHash = hashOf(this.body);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Publishes this version, from an instant it starts governing.
     *
     * <p>{@code effectiveFrom} may be in the future: a change to the creator agreement that
     * everybody should be told about a fortnight before it bites is a publication now with
     * a later effective date, not a reminder in somebody's calendar. {@link #governsAt}
     * is what every reader asks, and it consults the date rather than the publication.
     *
     * @throws PublishedDocumentIsImmutableException when it has already been published —
     *     which is the second half of the immutability rule and the half a service check
     *     would be tempted to make a no-op
     */
    public void publish(UUID publisher, Instant effectiveFrom, Instant now) {
        requireDraft();
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        this.publishedAt = Objects.requireNonNull(now, "now");
        this.publishedBy = publisher;
        this.updatedAt = now;
    }

    /** Whether this version is published and its effective date has arrived. */
    public boolean governsAt(Instant instant) {
        return publishedAt != null && effectiveFrom != null && !instant.isBefore(effectiveFrom);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * SHA-256 of the UTF-8 bytes, lower-case hex.
     *
     * <p>Public and static so that a test, and #429's signature check, can compute the same
     * value the same way without owning an instance. A second implementation of this
     * sentence somewhere else is how a signature stops verifying.
     */
    public static String hashOf(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; the checked exception is an artefact of the API.
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private void requireDraft() {
        if (publishedAt != null) {
            throw new PublishedDocumentIsImmutableException(id, kind, locale, version);
        }
    }

    private static String requireSupported(String locale) {
        if (!ReaderLocale.supported(locale)) {
            throw new IllegalArgumentException(
                    "The platform publishes in " + String.join(", ", ReaderLocale.SUPPORTED) + "; got " + locale);
        }
        return locale;
    }

    private static String requireText(String value, int max, String field) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new IllegalArgumentException(
                    "A document's " + field + " is between 1 and " + max + " characters; got " + trimmed.length());
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public String getLocale() {
        return locale;
    }

    public int getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
