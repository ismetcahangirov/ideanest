package az.ideanest.payout.api;

import az.ideanest.payout.application.PayoutService;
import az.ideanest.payout.domain.PayoutState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-05 payout queue over HTTP — issues #69 and #306.
 *
 * <h2>Five verbs, and each is a different authority</h2>
 *
 * <p>Reading needs {@code VIEW_FINANCE}. Calculating needs it too — it moves no money, and
 * a finance member of staff has to be able to answer a creator asking what they will be
 * paid. <strong>Approving, sending and cancelling need {@code APPROVE_PAYOUT}</strong>,
 * which {@code FINANCE} deliberately does not confer: §4.11 requires dual approval above a
 * threshold, and a role granting both issuing and approving would make the second
 * signature a formality whenever the finance team is one person.
 *
 * <h2>Approving is a POST and not a PUT</h2>
 *
 * <p>Unlike a staff role grant, which is a state and therefore idempotent under
 * {@code PUT}. A signature is an event: it happens at a moment, by a person, and the
 * response says how many more are needed. Signing twice is still a no-op — V55's primary
 * key sees to that — but the verb should not suggest that the second call replaced the
 * first.
 *
 * <p><strong>{@code no-store}</strong>: these responses carry what the platform is about
 * to pay out, and to whom.
 */
@RestController
@RequestMapping("/v1/admin/payouts")
public class PayoutController {

    private static final int PAGE_SIZE = 50;

    private final PayoutService payouts;
    private final Clock clock;

    public PayoutController(PayoutService payouts, Clock clock) {
        this.payouts = payouts;
        this.clock = clock;
    }

    /**
     * The queue: everything still on its way, oldest first.
     *
     * <p>Includes payouts whose hold has not expired, and says so per row — hiding them
     * would make a creator's "when will I be paid" unanswerable for the length of the hold.
     */
    @GetMapping("/queue")
    public ResponseEntity<PayoutResponses.PayoutPage> queue(
            @AuthenticationPrincipal Jwt accessToken, @RequestParam(defaultValue = "0") int page) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutPage.of(
                        payouts.queue(callerOf(accessToken), page), page, PAGE_SIZE, clock.instant()));
    }

    /** Everything, newest first, optionally narrowed to one state. */
    @GetMapping
    public ResponseEntity<PayoutResponses.PayoutPage> list(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(required = false) PayoutState state,
            @RequestParam(defaultValue = "0") int page) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutPage.of(
                        payouts.list(callerOf(accessToken), state, page), page, PAGE_SIZE, clock.instant()));
    }

    /** One payout with its signatures. */
    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutResponses.PayoutFile> inspect(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID payoutId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutFile.of(
                        payouts.inspect(callerOf(accessToken), payoutId), clock.instant()));
    }

    /** Works out what a campaign owes, and starts the hold. */
    @PostMapping
    public ResponseEntity<PayoutResponses.PayoutSummary> calculate(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody CalculateRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutSummary.of(
                        payouts.calculate(callerOf(accessToken), request.projectId()), clock.instant()));
    }

    /** Signs off. */
    @PostMapping("/{payoutId}/approvals")
    public ResponseEntity<PayoutResponses.PayoutFile> approve(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID payoutId,
            @Valid @RequestBody(required = false) ApproveRequest request) {

        String note = request == null ? null : request.note();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutFile.of(
                        payouts.approve(callerOf(accessToken), payoutId, note), clock.instant()));
    }

    /**
     * Takes a signature back.
     *
     * <p>The caller's own, and no path parameter names whose. Withdrawing somebody else's
     * approval would be one member of staff overruling another silently, and the platform
     * has no rule saying who may — so the endpoint does not offer it.
     */
    @DeleteMapping("/{payoutId}/approvals")
    public ResponseEntity<PayoutResponses.PayoutFile> withdrawApproval(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID payoutId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutFile.of(
                        payouts.withdrawApproval(callerOf(accessToken), payoutId), clock.instant()));
    }

    /** Instructs the provider. */
    @PostMapping("/{payoutId}/send")
    public ResponseEntity<PayoutResponses.PayoutSummary> send(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID payoutId,
            @Valid @RequestBody SendRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutSummary.of(
                        payouts.send(callerOf(accessToken), payoutId, request.destinationReference()),
                        clock.instant()));
    }

    /** Withdraws a payout before it is sent. */
    @PostMapping("/{payoutId}/cancel")
    public ResponseEntity<PayoutResponses.PayoutSummary> cancel(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID payoutId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PayoutResponses.PayoutSummary.of(
                        payouts.cancel(callerOf(accessToken), payoutId), clock.instant()));
    }

    /** Which campaign to work out a payout for. */
    public record CalculateRequest(@NotNull UUID projectId) {
    }

    /** Why this payout is being approved, for the next person to read. */
    public record ApproveRequest(@Size(max = 2000) String note) {
    }

    /**
     * Where the money goes.
     *
     * <p><strong>Supplied per send rather than stored on the creator's account</strong>,
     * because there is no payout-destination schema yet — §9 describes one and nothing
     * implements it. That is a real gap and this is the honest shape of it: the person
     * sending the money types where it goes, and the value is not persisted anywhere,
     * because a bank reference stored in a table nobody designed is worse than one that is
     * typed. It never appears in a log — {@code PayoutRequest.toString} redacts it.
     */
    public record SendRequest(@NotBlank @Size(max = 200) String destinationReference) {
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
