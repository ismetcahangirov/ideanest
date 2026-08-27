package az.ideanest.verification.api;

import az.ideanest.verification.domain.DocumentKind;
import az.ideanest.verification.domain.IdentityDocument;
import az.ideanest.verification.domain.IdentityVerification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Identity verification, as a creator and as a reviewer read it — issue #105.
 *
 * <h2>The same shape for both, and what that decides</h2>
 *
 * <p>A creator sees their own verification; a reviewer sees somebody else's. The body is
 * identical, and that is possible only because <strong>nothing in it is a secret from the
 * creator</strong> — the state, the decision, the reason, what kinds of document are held.
 * The moment a reviewer needs a field a creator must not see, this splits into two records
 * rather than growing a nullable field, because a nullable field is one somebody eventually
 * serialises for the wrong audience.
 *
 * <p>The document entries carry <strong>no bytes and no filename</strong>. Bytes come from
 * one audited endpoint, and a filename is a string the creator's own device chose which
 * would then be shown back to a reviewer — occasionally with a name in it.
 */
public final class VerificationResponses {

    private VerificationResponses() {}

    /** One document, as the review screen lists it before anything is opened. */
    public record Document(UUID id, String kind, String contentType, int byteLength, Instant uploadedAt) {

        public static Document of(IdentityDocument document) {
            return new Document(
                    document.getId(),
                    document.getKind().name(),
                    document.getContentType(),
                    document.getByteLength(),
                    document.getUploadedAt());
        }
    }

    /**
     * One verification.
     *
     * @param accepts what this subject may submit, so a client does not have to reimplement
     *     {@code DocumentKind}'s mapping and drift from it
     * @param documentsErasedAt when the documents were destroyed, or null while they are
     *     held. <strong>Shown to the creator on purpose</strong>: §17.4's minimisation is
     *     worth being visible, and a platform that quietly deleted a passport photograph
     *     without saying so is one whose retention limit nobody can check
     */
    public record Verification(
            UUID id,
            String state,
            String subjectKind,
            List<String> accepts,
            String rejectionReason,
            Instant reviewedAt,
            Instant expiresAt,
            Instant documentsErasedAt,
            List<Document> documents) {

        public static Verification of(IdentityVerification verification, List<IdentityDocument> documents) {
            return new Verification(
                    verification.getId(),
                    verification.getState().name(),
                    verification.getSubjectKind().name(),
                    DocumentKind.forSubject(verification.getSubjectKind()).stream()
                            .map(Enum::name)
                            .sorted()
                            .toList(),
                    verification.getRejectionReason() == null
                            ? null
                            : verification.getRejectionReason().name(),
                    verification.getReviewedAt(),
                    verification.getExpiresAt(),
                    verification.getDocumentsErasedAt(),
                    documents.stream().map(Document::of).toList());
        }
    }

    /**
     * The review queue.
     *
     * <p>No cursor, for {@code RiskResponses.Queue}'s reason: the queue is bounded by how
     * fast people work rather than by how long the platform has run, and a second page
     * means the queue is not being worked — which pagination does not fix.
     */
    public record Queue(List<Verification> verifications) {

        public static Queue of(List<Verification> verifications) {
            return new Queue(verifications);
        }
    }
}
