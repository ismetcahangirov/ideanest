package az.ideanest.pledge.api;

import java.util.UUID;

/**
 * What a client sends to {@code POST /v1/pledges/{id}/confirm}.
 *
 * <p>One nullable field, and it will stay one field. §9.2's phase 1 happens between
 * the client and the payment provider — 3-D Secure is a redirect or an SDK, and card
 * data never reaches our servers (§17.2's SAQ A) — so what arrives here is at most a
 * reference to a card the provider is holding.
 *
 * <p><strong>Nullable until #55, which is blocked on #60.</strong> There is no
 * {@code payment_methods} table to name and no provider to have tokenised anything,
 * so today this is accepted, stored on {@code pledges.payment_method_id} — a nullable
 * column with no foreign key, deliberately, see V17 — and resolved by nothing. The
 * shape a client sends therefore does not change when the card lands, which is the
 * point of accepting it now.
 *
 * <p>The whole body may be omitted for the same reason.
 */
public record ConfirmPledgeRequest(UUID paymentMethodId) {

    /** An empty body and no body are the same request. */
    public static ConfirmPledgeRequest orEmpty(ConfirmPledgeRequest request) {
        return request == null ? new ConfirmPledgeRequest(null) : request;
    }
}
