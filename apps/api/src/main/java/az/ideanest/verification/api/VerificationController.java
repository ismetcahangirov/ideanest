package az.ideanest.verification.api;

import az.ideanest.verification.application.DocumentRefusedException;
import az.ideanest.verification.application.IdentityVerifications;
import az.ideanest.verification.domain.DocumentKind;
import az.ideanest.verification.domain.IdentityVerification;
import az.ideanest.verification.domain.SubjectKind;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A creator's own identity verification — issue #105.
 *
 * <h2>Whose verification is never in the request</h2>
 *
 * <p>There is no account in the path and none in the body. The access token's subject is
 * the whole of the authorisation, exactly as on {@code NotificationPreferenceController}:
 * an identifier here would be the only field that mattered, and reading somebody else's
 * identity check is the entire attack.
 *
 * <p>There is no verification identifier either. A creator has one, it is theirs, and an
 * endpoint that took its identifier would be one somebody could enumerate.
 *
 * <h2>Multipart, and why the type is not taken from the part</h2>
 *
 * <p>A document is bytes; a JSON body would mean base64, a third more of everything, held
 * twice in memory while it is decoded. The part's declared {@code Content-Type} is read and
 * discarded — {@code DocumentBytes} decides from the content, per §17.3, and its own
 * comment argues why the client's word is the wrong thing to trust even when the client is
 * honest.
 *
 * <h2>{@code no-store} on both</h2>
 *
 * <p>The response says whether this person is being identity-checked and why they were
 * refused. That is not a document any shared cache should hold, and a browser's back-forward
 * cache holding it is a screen a shared laptop shows the next person.
 */
@RestController
@RequestMapping("/v1/me/verification")
public class VerificationController {

    private final IdentityVerifications verifications;

    public VerificationController(IdentityVerifications verifications) {
        this.verifications = verifications;
    }

    /**
     * This account's verification, and what it may submit.
     *
     * @param subjectKind whether this creator is a person or a company. Named on the read
     *     because it decides what the response says may be submitted, and because a client
     *     that had to submit a document to find out would be guessing
     */
    @GetMapping
    public ResponseEntity<VerificationResponses.Verification> mine(
            @AuthenticationPrincipal Jwt accessToken, @RequestParam(required = false) String subjectKind) {

        UUID userId = callerOf(accessToken);
        IdentityVerification verification =
                verifications.forCreator(userId, SubjectKind.parse(subjectKind).orElse(SubjectKind.INDIVIDUAL));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(VerificationResponses.Verification.of(
                        verification, verifications.documentsOf(verification.getId())));
    }

    /**
     * Submits one document.
     *
     * <p>Answers the whole verification rather than the document that was stored, because
     * the submission moves the state and a client that had to make a second call to find
     * out would render a stale screen in between.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationResponses.Verification> submit(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam String kind,
            @RequestParam(required = false) String subjectKind,
            @RequestParam("file") MultipartFile file) {

        UUID userId = callerOf(accessToken);

        DocumentKind documentKind = DocumentKind.parse(kind)
                .orElseThrow(() -> new DocumentRefusedException(
                        DocumentRefusedException.Reason.UNSUPPORTED_TYPE,
                        "That is not a kind of document this service takes."));
        SubjectKind subject = SubjectKind.parse(subjectKind).orElse(SubjectKind.INDIVIDUAL);

        verifications.submit(userId, subject, documentKind, bytesOf(file));

        IdentityVerification verification = verifications.forCreator(userId, subject);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(VerificationResponses.Verification.of(
                        verification, verifications.documentsOf(verification.getId())));
    }

    /**
     * The part's bytes.
     *
     * <p>An unreadable part is refused as empty rather than as a server error: the
     * realistic cause is a connection that dropped mid-upload, which is the creator's
     * network rather than our fault, and a 500 would tell them to report a bug.
     */
    private static byte[] bytesOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentRefusedException(DocumentRefusedException.Reason.EMPTY, "That file is empty.");
        }
        try {
            return file.getBytes();
        } catch (IOException interrupted) {
            throw new DocumentRefusedException(
                    DocumentRefusedException.Reason.EMPTY, "That file did not arrive completely.");
        }
    }

    /** Whoever is signed in. The whole of the authorisation on both endpoints. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
