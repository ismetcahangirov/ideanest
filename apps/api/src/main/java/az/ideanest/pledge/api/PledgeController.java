package az.ideanest.pledge.api;

import az.ideanest.pledge.PledgeProperties;
import az.ideanest.pledge.application.PledgeService;
import az.ideanest.shared.idempotency.IdempotencyKey;
import az.ideanest.shared.idempotency.IdempotentRequests;
import az.ideanest.shared.idempotency.RecordedResponse;
import az.ideanest.shared.ratelimit.RateLimitExceededException;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A backer's pledge: making one, reading it, and confirming it.
 *
 * <p>Three of §10.2's six pledge endpoints. {@code PATCH} and {@code DELETE} are #56
 * and {@code GET /receipt} waits on there being a collection to receipt.
 *
 * <p><strong>Both mutations require {@code Idempotency-Key}</strong> (§10.3), and the
 * requirement is enforced before anything else about the request is considered. A
 * missing or malformed key is a refusal rather than a request that quietly runs
 * without protection — see {@link IdempotencyKey}.
 *
 * <p><strong>They answer with recorded bytes, not with an object.</strong> Both
 * return {@code ResponseEntity<String>} carrying JSON that
 * {@link IdempotentRequests} produced and stored, so that a replay is byte-identical
 * to the response it replays. Serialising the result twice — once now, once from a
 * re-read on the retry — would make the guarantee only as good as the two
 * serialisations agreeing, which they stop doing the first time a field is added.
 * The read below has nothing to replay and returns the object.
 *
 * <p><strong>Authorisation is not decided here.</strong> The caller's identifier goes
 * down to {@link PledgeService}, whose reads are scoped to it in the query — so
 * somebody else's pledge is a 404 rather than a 403, for the reason
 * {@code PledgeNotFoundException} gives.
 *
 * <p><strong>No {@code Location} header on the draft.</strong> There is one — the
 * pledge is readable at {@code /v1/pledges/{id}} — but the whole resource is in the
 * body, and a client that has to follow a header to learn what it will be charged is
 * one round trip worse off at the checkout.
 */
@RestController
public class PledgeController {

    /**
     * What a key is spent on, and part of its fingerprint.
     *
     * <p>Named for the operation rather than for the path, so that a key cannot serve
     * a draft and a confirmation as though they were one request — and so that #56's
     * {@code pledge.edit} and {@code pledge.cancel} sit beside these rather than
     * being invented again.
     */
    private static final String DRAFT = "pledge.draft";

    private static final String CONFIRM = "pledge.confirm";

    private final PledgeService pledges;
    private final IdempotentRequests idempotency;
    private final RateLimiter rateLimiter;
    private final PledgeProperties properties;

    public PledgeController(
            PledgeService pledges,
            IdempotentRequests idempotency,
            RateLimiter rateLimiter,
            PledgeProperties properties) {
        this.pledges = pledges;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Reserves the place, quotes the selection, and answers with the draft.
     *
     * <p>{@code 201 Created}, and the same {@code 201} with the same body for every
     * retry carrying the same key.
     */
    @PostMapping(path = "/v1/pledges/draft", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> draft(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DraftPledgeRequest request) {

        UUID backerId = callerOf(accessToken);
        // Before the key is even parsed. §17.3's limit bounds the work one caller can
        // demand, and validating a header first would mean a client looping without
        // one is refused by something that does no counting.
        enforcePledgeRateLimit(backerId);

        IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
        return recorded(idempotency.execute(
                backerId,
                DRAFT,
                key,
                request,
                HttpStatus.CREATED.value(),
                () -> PledgeResponse.of(pledges.draft(request.toCommand(backerId, key.value())))));
    }

    /** The backer's own pledge. Somebody else's is a 404. */
    @GetMapping("/v1/pledges/{id}")
    public PledgeResponse read(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        return PledgeResponse.of(pledges.read(id, callerOf(accessToken)));
    }

    /**
     * §6.2's {@code DRAFT --> CONFIRMED}: the held place becomes a claimed one.
     *
     * <p><strong>Nothing is charged.</strong> §9.2 puts the collection at the close of
     * a successful campaign and says in terms that no money moves here and no ledger
     * entry is written. The response says {@code cardVerified: false} because even the
     * verification of §9.2's phase 1 is not built — {@code PledgeCapability}.
     *
     * <p>The body is optional: {@code paymentMethodId} is nullable until #55, so a
     * client with nothing to send may send nothing.
     */
    @PostMapping(path = "/v1/pledges/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> confirm(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ConfirmPledgeRequest body) {

        UUID backerId = callerOf(accessToken);
        enforcePledgeRateLimit(backerId);

        ConfirmPledgeRequest request = ConfirmPledgeRequest.orEmpty(body);
        IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
        return recorded(idempotency.execute(
                backerId,
                // The pledge is part of what the key was spent on, so one key cannot
                // confirm two different pledges — the second would otherwise be
                // replayed with the first one's body, which is a client being told a
                // pledge it never confirmed is confirmed.
                CONFIRM + ":" + id,
                key,
                request,
                HttpStatus.OK.value(),
                () -> PledgeResponse.of(pledges.confirm(id, backerId, request.paymentMethodId()))));
    }

    /**
     * The bytes that were recorded, with the status they were recorded under.
     *
     * <p>{@code String} rather than the object, so that the response and its replay
     * are the same bytes and not two serialisations of the same idea.
     */
    private static ResponseEntity<String> recorded(RecordedResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    /** §17.3: ten pledges a minute per account. */
    private void enforcePledgeRateLimit(UUID backerId) {
        PledgeProperties.RateLimit limits = properties.rateLimit();
        RateLimitDecision decision =
                rateLimiter.recordAttempt("pledge:" + backerId, limits.pledgesPerUser(), limits.window());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }

    /** The account making the request, as our own signature establishes it. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
