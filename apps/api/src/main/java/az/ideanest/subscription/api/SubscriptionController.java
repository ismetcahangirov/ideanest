package az.ideanest.subscription.api;

import az.ideanest.subscription.application.SubscriptionPlans;
import az.ideanest.subscription.application.Subscriptions;
import az.ideanest.subscription.application.UnknownPlanException;
import az.ideanest.subscription.domain.Subscription;
import az.ideanest.subscription.domain.SubscriptionPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue, and what one account holds against it.
 *
 * <h2>Two prefixes on one controller, and the reason they belong together</h2>
 *
 * <p>{@code GET /v1/plans} is public and {@code /v1/me/subscription} is not, which usually
 * argues for two controllers. They are one here because the pricing page asks both in the
 * same render and the two answers have to agree about what a plan is: a creator sees "you
 * are on Growth" beside the Growth card, and a plan serialised two ways by two controllers
 * is a card that eventually disagrees with the badge on it.
 *
 * <h2>The catalogue is cacheable and everything else is not</h2>
 *
 * <p>A price list is the same for everybody and changes a few times a year, so it carries
 * a short public max-age. Everything under {@code /v1/me} is {@code no-store}, like every
 * per-person response in this service.
 *
 * <h2>Buying is two steps for a priced plan, and the response says which step it is in</h2>
 *
 * <p>Nothing here charges a card — §9.2 ships no provider adapter while #60 is unanswered.
 * A priced plan comes back {@code PENDING_PAYMENT} with {@code entitled: false}, and the
 * pricing page tells the creator what happens next. A free plan comes back {@code ACTIVE}.
 * The client branches on {@code entitled} rather than on the price, so the day a provider
 * arrives the page needs no change.
 */
@RestController
public class SubscriptionController {

    private final SubscriptionPlans plans;
    private final Subscriptions subscriptions;
    private final Clock clock;

    public SubscriptionController(SubscriptionPlans plans, Subscriptions subscriptions, Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    /**
     * The plans on sale.
     *
     * <p>Public. A price list behind authentication is one nobody can decide to buy from,
     * and it is also what the marketing site links to.
     *
     * <p>Five minutes, public. Long enough that the page is served from a cache under any
     * load worth caring about; short enough that an operator who repriced a plan does not
     * spend an afternoon being told the browser is broken.
     */
    @GetMapping("/v1/plans")
    public ResponseEntity<SubscriptionResponses.Catalogue> catalogue() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(SubscriptionResponses.Catalogue.of(plans.onSale()));
    }

    /**
     * What the caller holds, or nothing.
     *
     * <p>200 with a null subscription rather than 404 — {@code SubscriptionResponses.Mine}
     * says why.
     */
    @GetMapping("/v1/me/subscription")
    public ResponseEntity<SubscriptionResponses.Mine> mine(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(describe(callerOf(accessToken)));
    }

    /**
     * Buys a plan.
     *
     * <p>201, because a subscription is a thing that now exists whichever state it is in.
     * A 200 for the pending case and a 201 for the active one would make the status code a
     * second, worse copy of the field the client is already reading.
     */
    @PostMapping("/v1/me/subscription")
    public ResponseEntity<SubscriptionResponses.Mine> subscribe(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody SubscribeRequest request) {

        UUID accountId = callerOf(accessToken);
        subscriptions.subscribe(accountId, request.planId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(describe(accountId));
    }

    /**
     * Cancels: keeps the period that was paid for, stops the renewal.
     *
     * <p>{@code DELETE} on a resource that does not disappear, which is worth one line of
     * defence. The alternative is {@code POST /v1/me/subscription/cancel}, and it was not
     * taken because what the creator means is "I no longer want this" — the subscription
     * outliving their request by three weeks is the platform being fair about a month they
     * paid for, not the request being something other than a deletion.
     *
     * <p>200 rather than 204, because the response is the point: it carries the date the
     * entitlement actually stops, which is the one thing the creator wants to know and
     * cannot work out from the request.
     */
    @DeleteMapping("/v1/me/subscription")
    public ResponseEntity<SubscriptionResponses.Mine> cancel(@AuthenticationPrincipal Jwt accessToken) {
        UUID accountId = callerOf(accessToken);
        subscriptions.cancel(accountId);

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(describe(accountId));
    }

    /** @param planId which plan, from {@code GET /v1/plans} */
    public record SubscribeRequest(@NotNull UUID planId) {
    }

    /**
     * Re-reads what the account holds and renders it.
     *
     * <p>Read back rather than composed from what the service returned, so that the three
     * endpoints cannot disagree about the shape of an answer they all give. The plan is
     * looked up by identifier — including an unlisted one, which is exactly the case a
     * catalogue lookup would miss.
     */
    private SubscriptionResponses.Mine describe(UUID accountId) {
        Instant now = clock.instant();

        return subscriptions
                .heldBy(accountId)
                .map(held -> new SubscriptionResponses.Mine(
                        SubscriptionResponses.Held.of(held, planOf(held), now)))
                .orElse(SubscriptionResponses.Mine.NONE);
    }

    private SubscriptionPlan planOf(Subscription subscription) {
        return plans.byId(subscription.getPlanId())
                .orElseThrow(() -> new UnknownPlanException(subscription.getPlanId()));
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
