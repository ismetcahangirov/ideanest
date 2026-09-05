package az.ideanest.legal.api;

import az.ideanest.legal.application.LegalAgreements;
import az.ideanest.shared.legal.AgreementInForce;
import az.ideanest.shared.legal.AgreementKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * What this account has agreed to, and how it agrees — issues #425, #426.
 *
 * <h2>Why an endpoint exists at all</h2>
 *
 * <p>The backer agreement is accepted inside the pledge flow, because §22.3 requires the
 * risk be stated there and nowhere else. The creator agreement has no such moment: a
 * campaign is drafted over weeks and submitted once, and #426's gate is on the submission.
 *
 * <p>Without somewhere to accept it, that gate would be unsatisfiable — a refusal telling
 * somebody to accept a document with no way of accepting it is worse than no gate at all.
 * So the editor reads {@link #mine}, shows the agreement, and posts {@link #accept}.
 *
 * <h2>What #429 changes, and what it does not</h2>
 *
 * <p>#429 makes the creator agreement's acceptance carry a SİMA İmza signature, which is an
 * acceptance with the legal force of a handwritten one rather than a tick. That is a
 * stronger acceptance recorded in the same row — {@code document_acceptances.signature_id}
 * exists and is null today — so this route's shape survives it and the client's flow gains
 * a step rather than being replaced.
 *
 * <p><strong>{@code no-store}.</strong> Everything here is about one account.
 */
@RestController
public class MyAgreementController {

    private final LegalAgreements agreements;

    public MyAgreementController(LegalAgreements agreements) {
        this.agreements = agreements;
    }

    /**
     * The gated agreements, what is in force, and whether this account has accepted it.
     *
     * <p>Both of them, always, rather than one per request. There are two, a client that
     * shows the editor wants one and a client that shows the checkout wants the other, and
     * two round trips to learn two booleans would be two round trips.
     *
     * <p>An agreement with nothing published is reported as {@code inForce: false} rather
     * than omitted, so a client can tell "not required yet" from "this response is missing a
     * field". That distinction is the whole of the fail-open behaviour, made visible.
     */
    @GetMapping(path = "/v1/me/agreements", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MyAgreements> mine(@AuthenticationPrincipal Jwt accessToken) {
        UUID accountId = callerOf(accessToken);

        List<MyAgreement> mine = new ArrayList<>();
        for (AgreementKind kind : AgreementKind.values()) {
            Optional<AgreementInForce> agreement = agreements.inForce(kind);
            if (agreement.isEmpty()) {
                mine.add(new MyAgreement(kind.name(), false, 0, null, null, null));
                continue;
            }
            AgreementInForce required = agreement.get();
            Optional<Instant> accepted = agreements.acceptedAt(accountId, required);
            mine.add(new MyAgreement(
                    kind.name(),
                    true,
                    required.version(),
                    required.documentId(),
                    required.effectiveFrom(),
                    accepted.orElse(null)));
        }

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MyAgreements(mine));
    }

    /**
     * Accepts the version in force of one agreement.
     *
     * <p>The version is in the body and is checked, not inferred. Recording "they accepted
     * whatever was current when the request arrived" would put an acknowledgement of version
     * 4 against somebody who read version 3 on a page they opened this morning — and a
     * refusal is a page reload, which is a thing a client can do without asking anybody.
     *
     * <p>Idempotent. Accepting twice is accepting once: V65's unique index makes it so, and
     * a client retrying must not be told it has agreed twice.
     */
    @PostMapping(
            path = "/v1/me/agreements/{kind}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MyAgreement> accept(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable AgreementKind kind,
            @Valid @RequestBody AcceptRequest request) {

        UUID accountId = callerOf(accessToken);
        AgreementInForce accepted = agreements.acceptOwn(accountId, kind, request.version());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new MyAgreement(
                        kind.name(),
                        true,
                        accepted.version(),
                        accepted.documentId(),
                        accepted.effectiveFrom(),
                        agreements.acceptedAt(accountId, accepted).orElse(null)));
    }

    /**
     * @param inForce false when nothing of this kind has been published. Everything below is
     *     then absent, and no gate asks for it
     * @param acceptedAt when this account accepted the version in force, or null if it has
     *     not. The version matters: an account that accepted version 3 and is looking at
     *     version 4 reads null here, which is exactly what the editor needs to know
     */
    public record MyAgreement(
            String document,
            boolean inForce,
            int version,
            UUID documentId,
            Instant effectiveFrom,
            Instant acceptedAt) {
    }

    /** Both gated agreements, in one answer. */
    public record MyAgreements(List<MyAgreement> agreements) {
    }

    /** @param version the version the client showed, which has to be the one in force */
    public record AcceptRequest(@NotNull @Min(1) Integer version) {
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
