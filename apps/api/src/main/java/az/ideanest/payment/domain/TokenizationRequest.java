package az.ideanest.payment.domain;

import az.ideanest.shared.money.Money;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * §9.2's phase one, as an instruction: verify this backer's card and keep a token.
 *
 * <p><strong>Nothing calls this today.</strong> Phase one is #55, blocked on #60, and
 * §9.2 is explicit that a stub returning an approval would be worse than nothing —
 * it would make the path look finished and would tell clients that cards were
 * verified when no card was ever seen. The type exists because #61's deliverable is
 * an interface a provider change cannot leak out of, and an interface with a hole
 * where its first call should be is not one.
 *
 * @param backerId whose card. The provider needs a stable customer reference so that
 *     a second campaign reuses the same saved card rather than asking again
 * @param pledgeId which checkout this belongs to, so the verification can be
 *     reconciled against the pledge it was for
 * @param verificationAmount §9.3's R-05: zero where the provider supports a
 *     zero-value verification, and the smallest amount it does support otherwise. The
 *     authorisation is voided immediately either way — §9.2's fourth step — so this is
 *     a number the backer should never be charged
 * @param returnUrl where the provider sends the backer after 3-D Secure. §9.3's R-04
 *     is on the list for the liability shift, and the shift is the reason phase one
 *     is customer-initiated at all
 * @param idempotencyKey §9.3's R-08, on this call as on every other
 */
public record TokenizationRequest(
        UUID backerId, UUID pledgeId, Money verificationAmount, URI returnUrl, String idempotencyKey) {

    public TokenizationRequest {
        Objects.requireNonNull(backerId, "A saved card belongs to somebody");
        Objects.requireNonNull(pledgeId, "A verification is a verification for a checkout");
        Objects.requireNonNull(verificationAmount, "A verification names an amount, even when it is zero");
        Objects.requireNonNull(returnUrl, "3-D Secure returns the backer somewhere");
        if (verificationAmount.isNegative()) {
            throw new IllegalArgumentException("A verification cannot be for a negative amount");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("§9.3's R-08 requires an idempotency key on every payment mutation");
        }
    }
}
