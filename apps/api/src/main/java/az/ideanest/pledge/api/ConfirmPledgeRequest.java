package az.ideanest.pledge.api;

import java.util.UUID;

/**
 * What a client sends to {@code POST /v1/pledges/{id}/confirm}.
 *
 * <p><strong>{@code paymentMethodId}.</strong> §9.2's phase 1 happens between the client
 * and the payment provider — 3-D Secure is a redirect or an SDK, and card data never
 * reaches our servers (§17.2's SAQ A) — so what arrives here is at most a reference to a
 * card the provider is holding.
 *
 * <p>Nullable until #55, which is blocked on #60. There is no {@code payment_methods} table
 * to name and no provider to have tokenised anything, so today this is accepted, stored on
 * {@code pledges.payment_method_id} — a nullable column with no foreign key, deliberately,
 * see V17 — and resolved by nothing. The shape a client sends therefore does not change
 * when the card lands, which is the point of accepting it now.
 *
 * <p><strong>{@code acknowledgedAgreementVersion}</strong> — #427. The version of the backer
 * agreement the checkout showed, which §22.3 requires be shown <em>within the pledge
 * flow</em>. It is a version and not a boolean for one reason: a boolean would say "the
 * client ticked something", and what has to be recorded is which sentence the person read.
 * A page left open across a publication sends the old number and is refused, which is the
 * case the field exists for.
 *
 * <p>Nullable, and accepted as null while no backer agreement is published — which is this
 * repository's state until #439 seeds the text. {@code Agreements} argues why an
 * unpublished agreement is not a requirement rather than a platform-wide refusal.
 *
 * <p>The whole body may still be omitted, for both reasons together.
 */
public record ConfirmPledgeRequest(UUID paymentMethodId, Integer acknowledgedAgreementVersion) {

    /** An empty body and no body are the same request. */
    public static ConfirmPledgeRequest orEmpty(ConfirmPledgeRequest request) {
        return request == null ? new ConfirmPledgeRequest(null, null) : request;
    }
}
