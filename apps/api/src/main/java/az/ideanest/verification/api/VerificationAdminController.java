package az.ideanest.verification.api;

import az.ideanest.verification.application.IdentityVerifications;
import az.ideanest.verification.application.VerificationNotDecidableException;
import az.ideanest.verification.domain.IdentityVerification;
import az.ideanest.verification.domain.RejectionReason;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The identity review queue — §4.11's AD-02, issue #105.
 *
 * <h2>The document endpoint is the one to read carefully</h2>
 *
 * <p>It is the only route to an identity document's bytes on this platform, and everything
 * about it is deliberate:
 *
 * <ul>
 *   <li><strong>Staff only, and audited before the bytes are returned.</strong>
 *       {@code IdentityVerifications.openDocument} writes the row; the entity is the
 *       creator, because "who has looked at my passport" is a question they are entitled to
 *       have answered.
 *   <li><strong>{@code Content-Disposition: attachment}.</strong> Never {@code inline}. The
 *       type is decided from the bytes rather than from what was uploaded ({@code
 *       DocumentBytes}), but a stored file served inline on the console's own origin is one
 *       upload away from being a script that runs there.
 *   <li><strong>{@code no-store}, and it is not decorative here.</strong> An identity
 *       document in a proxy cache is an identity document outside the platform.
 *   <li><strong>The identifier is scoped to its verification in the query.</strong> A
 *       mismatched pair cannot be opened by a mistake in this class, because this class
 *       does not do the pairing.
 * </ul>
 *
 * <h2>Deciding erases</h2>
 *
 * <p>Approving or rejecting destroys the documents in the same transaction — §17.4, and
 * {@code IdentityVerifications} argues it. A reviewer who has finished looking has no
 * further need of the photograph, and "just in case" is how a seven-day limit becomes a
 * year.
 */
@RestController
@RequestMapping("/v1/admin/verifications")
public class VerificationAdminController {

    /** A page of the queue. Bounded because a queue nobody can work in a sitting is a backlog. */
    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    private final IdentityVerifications verifications;

    public VerificationAdminController(IdentityVerifications verifications) {
        this.verifications = verifications;
    }

    /** What is waiting to be reviewed, oldest first. */
    @GetMapping("/queue")
    public ResponseEntity<VerificationResponses.Queue> queue(
            @AuthenticationPrincipal Jwt accessToken, @RequestParam(required = false) Integer limit) {

        UUID staffId = callerOf(accessToken);
        List<VerificationResponses.Verification> page = verifications.queue(staffId, clamp(limit)).stream()
                .map(verification ->
                        VerificationResponses.Verification.of(verification, verifications.documentsOf(verification.getId())))
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(VerificationResponses.Queue.of(page));
    }

    /**
     * One document's bytes.
     *
     * <p>See the class comment. This is the endpoint the whole of #105's "restricted
     * access" is about.
     */
    @GetMapping("/{verificationId}/documents/{documentId}")
    public ResponseEntity<byte[]> document(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID verificationId,
            @PathVariable UUID documentId) {

        IdentityVerifications.OpenedDocument opened =
                verifications.openDocument(callerOf(accessToken), verificationId, documentId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(opened.contentType()))
                // Attachment, always. See the class comment: inline on the console's own
                // origin is one upload away from a script running there.
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                // A fixed name. The uploader's filename is a string their
                                // device chose and occasionally contains theirs.
                                .filename("document-" + documentId)
                                .build()
                                .toString())
                .body(opened.content());
    }

    /** A member of staff was satisfied. */
    @PostMapping("/{verificationId}/approve")
    public ResponseEntity<VerificationResponses.Verification> approve(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID verificationId) {

        IdentityVerification decided = verifications.approve(callerOf(accessToken), verificationId);
        return decided(decided);
    }

    /**
     * A member of staff was not satisfied.
     *
     * @param reason one of {@link RejectionReason}. Required, and refused when it is not one
     *     of them — a rejection with no reason is one the creator cannot act on, and a
     *     free-text one is untranslatable and is where somebody pastes what they saw
     */
    @PostMapping("/{verificationId}/reject")
    public ResponseEntity<VerificationResponses.Verification> reject(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID verificationId,
            @RequestParam String reason) {

        RejectionReason refusal = RejectionReason.parse(reason)
                .orElseThrow(() -> new VerificationNotDecidableException("That is not a refusal reason"));

        return decided(verifications.reject(callerOf(accessToken), verificationId, refusal));
    }

    /**
     * The verification after a decision, with an empty document list.
     *
     * <p>Empty because the decision erased them, and answering with the list is how a
     * reviewer sees that it did.
     */
    private ResponseEntity<VerificationResponses.Verification> decided(IdentityVerification verification) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(VerificationResponses.Verification.of(
                        verification, verifications.documentsOf(verification.getId())));
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** Whoever is signed in. Never the body — see {@code AuditTrailController}. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
