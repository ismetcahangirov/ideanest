package az.ideanest.compliance.api;

import az.ideanest.compliance.application.CreatorLegalSubjects;
import az.ideanest.compliance.domain.TaxIdentifier;
import az.ideanest.shared.compliance.LegalSubject;
import az.ideanest.shared.compliance.SubjectKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A creator records who they legally are — issue #430.
 *
 * <p>Under {@code /v1/me} rather than under a campaign, because the subject is a fact about the
 * account and outlives any one campaign. A creator with three campaigns has one legal subject,
 * and three copies of it, one per campaign, is the shape that eventually disagrees with itself.
 *
 * <p><strong>{@code PUT} and not {@code PATCH}.</strong> The fields are not independent: moving
 * from an individual to a legal entity arrives with three new ones and moving back must clear
 * them, so a partial update would need a rule for what a missing field means and the rule would
 * differ per field. The body that arrives is the row that results.
 */
@RestController
public class MyLegalSubjectController {

    private final CreatorLegalSubjects subjects;
    private final Clock clock;

    public MyLegalSubjectController(CreatorLegalSubjects subjects, Clock clock) {
        this.subjects = subjects;
        this.clock = clock;
    }

    @GetMapping(path = "/v1/me/legal-subject", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegalSubjectResponses.Mine> mine(@AuthenticationPrincipal Jwt accessToken) {
        UUID accountId = callerOf(accessToken);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subjects.mine(accountId)
                        .map(row -> LegalSubjectResponses.Mine.of(row.asLegalSubject(), row.getUpdatedAt()))
                        .orElseGet(LegalSubjectResponses.Mine::none));
    }

    @PutMapping(
            path = "/v1/me/legal-subject",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LegalSubjectResponses.Mine> record(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody LegalSubjectRequest request) {
        UUID accountId = callerOf(accessToken);
        LegalSubject recorded = subjects.record(accountId, request.toSubject());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LegalSubjectResponses.Mine.of(recorded, clock.instant()));
    }

    /**
     * What a creator sends.
     *
     * <p>The entity fields are optional at this layer even for a {@code LEGAL_ENTITY}, and
     * {@code isComplete()} is what a submission gate asks later. V70's header gives the reason:
     * a creator part-way through entering a company must be able to save, and a form that
     * refuses until the last field lands is a form that loses somebody's work.
     *
     * <p>The VÖEN is normalised on the way in — spaces and dashes stripped, then ten digits or
     * a refusal. A stored identifier that varies in punctuation cannot be compared, and #432
     * will compare it.
     */
    public record LegalSubjectRequest(
            @NotNull SubjectKind subjectKind,
            @NotBlank @Size(max = 200) String legalName,
            @Size(max = 20) String taxId,
            @Size(max = 500) String registeredAddress,
            @Size(max = 100) String registrationNumber) {

        LegalSubject toSubject() {
            String normalisedTaxId = taxId == null || taxId.isBlank() ? null : TaxIdentifier.normalise(taxId);
            return new LegalSubject(subjectKind, legalName, normalisedTaxId, registeredAddress, registrationNumber);
        }
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
