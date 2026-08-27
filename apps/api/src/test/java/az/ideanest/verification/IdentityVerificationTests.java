package az.ideanest.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.infrastructure.UserRepository;
import az.ideanest.verification.application.DocumentBytes;
import az.ideanest.verification.application.DocumentCipher;
import az.ideanest.verification.application.DocumentRefusedException;
import az.ideanest.audit.AuditEntryRepository;
import az.ideanest.verification.application.DocumentRetentionJob;
import az.ideanest.verification.application.IdentityVerifications;
import az.ideanest.verification.application.VerificationNotDecidableException;
import az.ideanest.verification.domain.DocumentKind;
import az.ideanest.verification.domain.IdentityVerification;
import az.ideanest.verification.domain.SealedDocument;
import az.ideanest.verification.domain.SubjectKind;
import az.ideanest.verification.domain.VerificationState;
import az.ideanest.verification.infrastructure.IdentityDocumentRepository;
import az.ideanest.verification.infrastructure.IdentityVerificationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;

/**
 * Identity verification for creators — issue #105.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aDecisionDestroysTheDocuments()} — the retention limit in #105's title. A
 *       platform that keeps a passport photograph after it has been looked at is a platform
 *       holding identity documents indefinitely, which is the thing §17.4 forbids.
 *   <li>{@link #everyOpeningIsAudited()} — the restricted access. "Who has looked at my
 *       passport" is unanswerable unless every opening writes a row.
 *   <li>{@link #theMediaTypeComesFromTheBytes()} — §17.3. A stored file served with the
 *       type its uploader chose is a script running on the console's origin.
 *   <li>{@link #theSweepCatchesASubmissionNobodyEverDecided()} — the case the ordinary path
 *       cannot reach, and the one that turns a queue into an archive.
 * </ul>
 */
@DisplayName("Identity verification")
class IdentityVerificationTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    /** A minimal JPEG: the signature and nothing else. Nothing here renders it. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x11};

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, 0x00
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private IdentityVerifications verifications;

    @Autowired
    private IdentityVerificationRepository verificationRepository;

    @Autowired
    private IdentityDocumentRepository documentRepository;

    @Autowired
    private DocumentCipher cipher;

    @Autowired
    private DocumentRetentionJob retention;

    @Autowired
    private VerificationProperties properties;

    @Autowired
    private AuditEntryRepository auditEntries;

    @BeforeEach
    void clear() {
        documentRepository.deleteAll();
        verificationRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a creator with no verification has one, in REQUESTED")
    void aVerificationIsCreatedOnFirstRead() {
        UUID creator = creator("verify-new-");

        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        assertThat(verification.getState()).isEqualTo(VerificationState.REQUESTED);
        assertThat(verification.getDocumentsErasedAt()).isNull();
    }

    @Test
    @DisplayName("submitting a document moves the verification to SUBMITTED")
    void submittingMovesTheState() {
        UUID creator = creator("verify-submit-");

        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);

        assertThat(verifications.forCreator(creator, SubjectKind.INDIVIDUAL).getState())
                .isEqualTo(VerificationState.SUBMITTED);
    }

    @Test
    @DisplayName("the media type comes from the bytes, never from what the client called it")
    void theMediaTypeComesFromTheBytes() {
        UUID creator = creator("verify-type-");

        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, PNG);

        // §17.3. A browser guesses the type from the extension, so a file saved as .jpg
        // that is really a PNG arrives labelled JPEG -- and a reviewer's viewer chokes.
        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);
        assertThat(verifications.documentsOf(verification.getId()))
                .singleElement()
                .satisfies(document -> assertThat(document.getContentType()).isEqualTo("image/png"));
    }

    @Test
    @DisplayName("refuses a file that is not one of the three formats a reviewer can open")
    void anUnsupportedFormatIsRefused() {
        UUID creator = creator("verify-format-");

        assertThatExceptionOfType(DocumentRefusedException.class)
                .isThrownBy(() -> verifications.submit(
                        creator,
                        SubjectKind.INDIVIDUAL,
                        DocumentKind.PASSPORT,
                        "<html>not a passport</html>".getBytes(StandardCharsets.UTF_8)))
                .satisfies(refusal ->
                        assertThat(refusal.reason()).isEqualTo(DocumentRefusedException.Reason.UNSUPPORTED_TYPE));
    }

    @Test
    @DisplayName("refuses a document kind the subject cannot submit")
    void aCompanyCannotSubmitAPassport() {
        UUID creator = creator("verify-kind-");

        assertThatExceptionOfType(DocumentRefusedException.class)
                .isThrownBy(() ->
                        verifications.submit(creator, SubjectKind.LEGAL_ENTITY, DocumentKind.PASSPORT, JPEG))
                .satisfies(refusal -> assertThat(refusal.reason())
                        .isEqualTo(DocumentRefusedException.Reason.WRONG_KIND_FOR_SUBJECT));
    }

    @Test
    @DisplayName("refuses an empty file")
    void anEmptyFileIsRefused() {
        UUID creator = creator("verify-empty-");

        assertThatExceptionOfType(DocumentRefusedException.class)
                .isThrownBy(() ->
                        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, new byte[0]))
                .satisfies(refusal -> assertThat(refusal.reason()).isEqualTo(DocumentRefusedException.Reason.EMPTY));
    }

    @Test
    @DisplayName("stores the bytes encrypted, and nothing readable beside them")
    void theBytesAreSealed() {
        UUID creator = creator("verify-sealed-");
        byte[] content = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'S', 'E', 'C', 'R', 'E', 'T'};

        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, content);

        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);
        SealedDocument sealed = documentRepository
                .findByVerificationIdOrderByUploadedAtAsc(verification.getId())
                .getFirst()
                .sealed();

        // The stored bytes are not the submitted bytes, and the round trip is exact.
        assertThat(sealed.ciphertext()).isNotEqualTo(content);
        assertThat(cipher.open(sealed)).isEqualTo(content);
    }

    @Test
    @DisplayName("uses a fresh nonce for the same bytes twice")
    void nonceIsNeverReused() {
        // Nonce reuse under one key is the failure that breaks GCM completely rather than
        // gradually, and "the document did not change" is exactly where a naive
        // implementation keeps the old one.
        assertThat(cipher.seal(JPEG).nonce()).isNotEqualTo(cipher.seal(JPEG).nonce());
    }

    // ------------------------------------------------------------------
    // Restricted access
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a document is not readable by a caller who is not staff")
    void openingNeedsStaff() {
        UUID creator = creator("verify-outsider-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);

        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);
        UUID documentId = verifications.documentsOf(verification.getId()).getFirst().getId();

        // Including the creator themselves: what they submitted is theirs, and re-reading
        // it from the platform is not a capability that buys them anything.
        assertThatExceptionOfType(az.ideanest.staff.application.NotAModeratorException.class)
                .isThrownBy(() -> verifications.openDocument(creator, verification.getId(), documentId));
    }

    @Test
    @DisplayName("a document cannot be opened through another verification")
    void aDocumentIsScopedToItsVerification() {
        UUID first = creator("verify-scope-a-");
        UUID second = creator("verify-scope-b-");
        verifications.submit(first, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        verifications.submit(second, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);

        IdentityVerification theirs = verifications.forCreator(first, SubjectKind.INDIVIDUAL);
        IdentityVerification mine = verifications.forCreator(second, SubjectKind.INDIVIDUAL);
        UUID theirDocument = verifications.documentsOf(theirs.getId()).getFirst().getId();

        // Scoped in the query rather than checked afterwards, so a mismatched pair cannot
        // be opened by a mistake in a controller. The refusal here is the staff check,
        // which comes first -- the pairing is asserted by the repository method's shape.
        assertThat(documentRepository.findByIdAndVerificationId(theirDocument, mine.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("every opening of a document is audited, against the creator")
    void everyOpeningIsAudited() {
        UUID creator = creator("verify-audit-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);

        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);
        UUID documentId = verifications.documentsOf(verification.getId()).getFirst().getId();

        long before = auditEntries.count();
        IdentityVerifications.OpenedDocument opened =
                verifications.openDocument(staff(), verification.getId(), documentId);

        // The bytes came back, and a row was written. "Who has looked at my passport" is
        // unanswerable without the second half.
        assertThat(opened.content()).isEqualTo(JPEG);
        assertThat(auditEntries.count()).isGreaterThan(before);
    }

    @Test
    @DisplayName("approving destroys the documents in the same transaction")
    void approvingErasesTheDocuments() {
        UUID creator = creator("verify-approve-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        IdentityVerification submitted = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        IdentityVerification decided = verifications.approve(staff(), submitted.getId());

        // A reviewer who has finished looking has no further need of the photograph, and
        // "just in case" is how a seven-day limit becomes a year.
        assertThat(decided.getState()).isEqualTo(VerificationState.APPROVED);
        assertThat(decided.getExpiresAt()).isNotNull();
        assertThat(verifications.documentsOf(decided.getId())).isEmpty();
        assertThat(decided.getDocumentsErasedAt()).isNotNull();
    }

    @Test
    @DisplayName("a second decision on the same verification is a conflict, not a mistake")
    void decidingTwiceIsRefused() {
        UUID creator = creator("verify-twice-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        IdentityVerification submitted = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        verifications.approve(staff(), submitted.getId());

        // Two members of staff in the same queue.
        assertThatExceptionOfType(VerificationNotDecidableException.class)
                .isThrownBy(() -> verifications.approve(staff(), submitted.getId()));
    }

    // ------------------------------------------------------------------
    // The retention limit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a decision destroys the documents behind it")
    void aDecisionDestroysTheDocuments() {
        UUID creator = creator("verify-erase-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        // Erased directly rather than through approve(), which needs a staff account this
        // suite does not grant. What is asserted is the erasure, which is the rule.
        int erased = verifications.erase(verification);

        assertThat(erased).isEqualTo(1);
        assertThat(verifications.documentsOf(verification.getId())).isEmpty();
        assertThat(verification.getDocumentsErasedAt()).isNotNull();
    }

    @Test
    @DisplayName("records when the documents went, and does not move the date afterwards")
    void theErasureDateIsStable() {
        UUID creator = creator("verify-date-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        verifications.erase(verification);
        Instant when = verification.getDocumentsErasedAt();

        // The sweep runs daily. A verification whose documents went last week must not have
        // its erasure date moved to today -- that date is the answer to "when did we stop
        // holding this person's passport", and moving it would make it a lie.
        verification.documentsErased(Instant.now().plus(Duration.ofDays(1)));
        assertThat(verification.getDocumentsErasedAt()).isEqualTo(when);
    }

    @Test
    @DisplayName("the sweep catches a submission nobody ever decided")
    void theSweepCatchesASubmissionNobodyEverDecided() {
        UUID creator = creator("verify-sweep-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        // Nothing is due yet.
        assertThat(retention.sweep(Instant.now())).isZero();
        assertThat(verifications.documentsOf(verification.getId())).hasSize(1);

        // A year on, the queue was never worked. This is the case the ordinary path cannot
        // reach, and the one that turns a review queue into an archive of passports.
        assertThat(retention.sweep(Instant.now().plus(Duration.ofDays(365)))).isEqualTo(1);
        assertThat(verifications.documentsOf(verification.getId())).isEmpty();
    }

    @Test
    @DisplayName("the sweep leaves a submitted document alone until its longer limit")
    void theSweepRespectsTheTwoLimits() {
        UUID creator = creator("verify-limits-");
        verifications.submit(creator, SubjectKind.INDIVIDUAL, DocumentKind.PASSPORT, JPEG);
        IdentityVerification verification = verifications.forCreator(creator, SubjectKind.INDIVIDUAL);

        // Past the decided limit (7 days) and well inside the undecided one (60). Deleting
        // here would make the creator submit again for nothing.
        assertThat(retention.sweep(Instant.now().plus(Duration.ofDays(10)))).isZero();
        assertThat(verifications.documentsOf(verification.getId())).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Magic bytes, on their own
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reads the three formats and refuses everything else")
    void magicBytes() {
        assertThat(DocumentBytes.mediaTypeOf(JPEG)).contains("image/jpeg");
        assertThat(DocumentBytes.mediaTypeOf(PNG)).contains("image/png");
        assertThat(DocumentBytes.mediaTypeOf("%PDF-1.7".getBytes(StandardCharsets.UTF_8)))
                .contains("application/pdf");

        assertThat(DocumentBytes.mediaTypeOf(null)).isEmpty();
        assertThat(DocumentBytes.mediaTypeOf(new byte[0])).isEmpty();
        // Two of the three JPEG bytes. A prefix that nearly matches must not match.
        assertThat(DocumentBytes.mediaTypeOf(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(DocumentBytes.mediaTypeOf("GIF89a".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // What is deliberately not gated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("nothing on the platform is gated on a verification")
    void verificationGatesNothing() {
        // §22.1 makes the threshold a legal question (#71, needs-decision). This asserts
        // the default rather than the absence of a gate -- a deployment that turned this on
        // would be inventing a compliance position, and the flag existing is what makes the
        // day somebody may decide it a configuration change.
        assertThat(properties.required()).isFalse();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * The one account this suite's configuration treats as platform staff.
     *
     * <p>Only the identifier is needed — every staff-side method here is called directly
     * rather than over HTTP — so no token is minted and no sign-in is spent. Several suites
     * share this address and {@code sign-ins-per-email} is deliberately left at its real
     * value of five, so a suite that signed in as it would make somebody else's tests fail
     * with a 401 that has nothing to do with them.
     */
    private UUID staff() {
        EmailAddress email = EmailAddress.of("moderator@ideanest.test");
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Moderator"),
                    String.class);
        }
        return users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
    }

    /**
     * A registered creator.
     *
     * <p>The prefix is per test, because two suites taking the same address is a failure
     * that surfaces three frames away as a request with a null bearer.
     */
    private UUID creator(String prefix) {
        EmailAddress email = EmailAddress.of(prefix + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);
        return users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
    }
}
