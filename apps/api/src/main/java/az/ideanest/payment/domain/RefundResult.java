package az.ideanest.payment.domain;

import java.util.Objects;

/**
 * What the provider said about a {@link RefundRequest}.
 *
 * <p>The same shape as {@link ChargeResult} and the same rule about failure codes,
 * because it is the same fact from the other direction. {@link ProviderOutcome#PENDING}
 * is the ordinary answer here rather than the unusual one: most providers accept a
 * refund immediately and settle it over the following days, and #67 will have to
 * treat the webhook as the moment it happened.
 */
public record RefundResult(
        ProviderOutcome outcome,
        String providerTransactionId,
        String failureCode,
        String failureMessage,
        String rawResponse) {

    public RefundResult {
        Objects.requireNonNull(outcome, "A refund result says what the provider decided");
        if ((outcome == ProviderOutcome.DECLINED) == (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("A refused refund says why, and one that was not refused does not");
        }
    }
}
