package az.ideanest.compliance.api;

import az.ideanest.compliance.application.ComplianceOverrides;
import az.ideanest.compliance.domain.ComplianceRequirement;
import az.ideanest.compliance.domain.OverrideReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §3.1's override rows over HTTP — issue #436.
 *
 * <h2>Under {@code /accounts}, because an override is about a person</h2>
 *
 * <p>Not under {@code /compliance}. Every one of these is read and written from the console's
 * account screen, beside the verification and the acceptance record, which is exactly where
 * #436 asks for it to appear: "on the account it was applied to where the next person to look
 * at that account will see it". A separate overrides screen would be a list nobody opens
 * except when they are already looking for one.
 *
 * <h2>DELETE revokes; it does not delete</h2>
 *
 * <p>The verb is the client's, and the row is never removed — V66 says why. An override that
 * was withdrawn after somebody used it is a different fact from one that was never granted,
 * and only the row can tell them apart. The response is the revoked override rather than 204,
 * so that the screen redraws from what the server now believes instead of from what it
 * assumed the request did.
 *
 * <p><strong>{@code no-store}</strong>, like every response under this prefix.
 */
@RestController
@RequestMapping("/v1/admin/accounts")
public class ComplianceOverrideController {

    private final ComplianceOverrides overrides;
    private final Clock clock;

    public ComplianceOverrideController(ComplianceOverrides overrides, Clock clock) {
        this.overrides = overrides;
        this.clock = clock;
    }

    /** Everything ever waived for this account, live and expired alike. */
    @GetMapping(path = "/{accountId}/compliance-overrides", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ComplianceOverrideResponses.History> history(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID accountId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ComplianceOverrideResponses.History.of(
                        accountId, overrides.forSubject(callerOf(accessToken), accountId), clock.instant()));
    }

    /**
     * Waives one requirement for this account until a date.
     *
     * <p>The subject comes from the path and the grantor from the token, which is what makes
     * the self-grant unrepresentable at this layer: there is no field in the body for
     * "granted by", so a caller cannot claim to be somebody else even before the constraint
     * refuses them.
     */
    @PostMapping(path = "/{accountId}/compliance-overrides", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ComplianceOverrideResponses.Override> grant(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID accountId,
            @Valid @RequestBody GrantRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ComplianceOverrideResponses.Override.of(
                        overrides.grant(
                                callerOf(accessToken),
                                accountId,
                                request.requirement(),
                                request.reason(),
                                request.note(),
                                request.expiresAt()),
                        clock.instant()));
    }

    /** Withdraws a live override before it would have expired. */
    @DeleteMapping(path = "/{accountId}/compliance-overrides/{overrideId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ComplianceOverrideResponses.Override> revoke(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID accountId, @PathVariable UUID overrideId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ComplianceOverrideResponses.Override.of(
                        overrides.revoke(callerOf(accessToken), overrideId), clock.instant()));
    }

    /**
     * @param note required, and V66 says why: a role grant's note is a message to the next
     *     administrator and may be empty, and this is the sentence an auditor reads
     * @param expiresAt when the exception ends. Required rather than defaulted, because a
     *     default would be the platform choosing how long a control stays off
     */
    public record GrantRequest(
            @NotNull ComplianceRequirement requirement,
            @NotNull OverrideReason reason,
            @NotBlank @Size(max = 2000) String note,
            @NotNull Instant expiresAt) {
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
