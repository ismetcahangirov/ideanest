package az.ideanest.compliance.api;

import az.ideanest.compliance.application.CreatorLegalSubjects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's read of somebody's legal subject — issue #430.
 *
 * <p><strong>Read-only, and on the account screen rather than as a new screen.</strong> #430
 * asks for it "beside the verification queue rather than as a new screen if that fits", and it
 * fits: {@code /v1/admin/accounts/{id}} is where the compliance overrides already hang, a new
 * console screen costs five separate registrations, and a subject with no queue of its own has
 * nothing to put on one.
 *
 * <p>No write. A member of staff correcting a creator's legal name would be the platform
 * asserting who somebody is on their behalf, and the whole of #429's name match assumes the
 * name came from the person it names. A wrong subject is corrected by the creator, or waived
 * by an override that says who waived it.
 */
@RestController
@RequestMapping("/v1/admin/accounts")
public class AdminLegalSubjectController {

    private final CreatorLegalSubjects subjects;

    public AdminLegalSubjectController(CreatorLegalSubjects subjects) {
        this.subjects = subjects;
    }

    @GetMapping(path = "/{accountId}/legal-subject", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegalSubjectResponses.ForStaff> forAccount(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID accountId) {
        UUID staffId = UUID.fromString(accessToken.getSubject());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LegalSubjectResponses.ForStaff.of(accountId, subjects.forStaff(staffId, accountId)));
    }
}
