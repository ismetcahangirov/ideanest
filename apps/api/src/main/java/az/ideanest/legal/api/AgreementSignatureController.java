package az.ideanest.legal.api;

import az.ideanest.legal.application.AgreementSigning;
import az.ideanest.shared.legal.AgreementKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing an agreement, and reading back what was signed — issue #429.
 *
 * <p>Three routes, and the shape is the provider's rather than a choice: a national e-signature
 * is answered on a phone, so the flow is start, poll, and — later, possibly much later — read.
 *
 * <ul>
 *   <li>{@code POST /v1/me/agreements/{kind}/signature} opens a session and returns the code
 *       the citizen compares against the one on their phone
 *   <li>{@code GET /v1/me/agreements/{kind}/signature/sessions/{sessionId}} says what became of
 *       it, and files the signature when there is one
 *   <li>{@code GET /v1/me/agreements/{kind}/signature} is what was signed, when, and which
 *       version — because "a signature nobody can retrieve is a signature nobody can rely on"
 * </ul>
 *
 * <p>The poll is a {@code GET} that can write, which is worth naming rather than hiding: the
 * write is not the caller's to cause and is not repeatable — a session resolves once, the
 * outcome is recorded, and a second poll reads the row. It is the shape #428's
 * {@code SignatureProvider.resolve} has, and the alternative — a {@code POST} the client sends
 * every two seconds — reads as though each poll were a decision.
 */
@RestController
public class AgreementSignatureController {

    private final AgreementSigning signing;

    public AgreementSignatureController(AgreementSigning signing) {
        this.signing = signing;
    }

    @PostMapping(
            path = "/v1/me/agreements/{kind}/signature",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SignatureResponses.SessionOpened> begin(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable AgreementKind kind,
            @Valid @RequestBody BeginRequest request) {
        AgreementSigning.Started started = signing.begin(
                callerOf(accessToken), kind, request.version(), request.fin(), request.mobile());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SignatureResponses.SessionOpened.of(kind, started));
    }

    @GetMapping(
            path = "/v1/me/agreements/{kind}/signature/sessions/{sessionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SignatureResponses.SessionProgress> resolve(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable AgreementKind kind,
            @PathVariable String sessionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SignatureResponses.SessionProgress.of(
                        kind, signing.resolve(callerOf(accessToken), kind, sessionId)));
    }

    @GetMapping(path = "/v1/me/agreements/{kind}/signature", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SignatureResponses.MySignature> mine(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable AgreementKind kind) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(signing.mine(callerOf(accessToken), kind)
                        .map(SignatureResponses.MySignature::of)
                        .orElseGet(() -> SignatureResponses.MySignature.unsigned(kind)));
    }

    /**
     * What a creator sends to start signing.
     *
     * <p>The version is named so that a creator cannot sign one version while the browser shows
     * another — {@code AgreementVersionStaleException} is the same guard {@code POST
     * /v1/me/agreements/{kind}} already applies to a tick.
     *
     * <p>The FİN and the mobile number are passed to the provider and not stored. V71's header
     * argues it: §17.4 asks for the data the purpose needs, and the purpose of a session row —
     * knowing what was to be signed and whether it has been answered — needs neither. The FİN
     * that is kept is the one on the certificate, which arrives with the signature.
     */
    public record BeginRequest(
            @NotNull @Min(1) Integer version,
            @NotBlank @Pattern(regexp = "^[0-9A-Za-z]{7}$", message = "A FİN is seven characters") String fin,
            @NotBlank @Size(max = 32) String mobile) {

        public BeginRequest {
            fin = fin == null ? null : fin.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
