package az.ideanest.payment.domain;

import java.util.Objects;

/**
 * What the provider said about a {@link PayoutRequest}.
 *
 * <p>Same shape and same rule as {@link ChargeResult} and {@link RefundResult}.
 * §6.3's {@code PROCESSING} state exists because {@link ProviderOutcome#PENDING} is
 * the usual answer: a bank transfer is accepted now and settles on a banking day,
 * and #69's job is to hold the payout in {@code PROCESSING} until #66's webhook says
 * otherwise rather than telling a creator they have been paid.
 */
public record PayoutResult(
        ProviderOutcome outcome,
        String providerTransactionId,
        String failureCode,
        String failureMessage,
        String rawResponse) {

    public PayoutResult {
        Objects.requireNonNull(outcome, "A payout result says what the provider decided");
        if ((outcome == ProviderOutcome.DECLINED) == (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("A refused payout says why, and one that was not refused does not");
        }
    }
}
