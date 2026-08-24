package az.ideanest.payment.application;

import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One provider call, as §4.11's AD-05 log shows it — #304.
 *
 * <p><strong>A projection and not the entity, for {@code AdministeredAccount}'s
 * reason.</strong> {@link PaymentTransaction} lives in this module's {@code domain}
 * package, and {@code ModuleBoundaryTests} forbids the admin module from naming anything
 * there. That rule is doing real work here rather than being satisfied for form's sake:
 * the entity carries {@code providerResponse}, which is the provider's document verbatim,
 * and a projection is the place that decision gets made once instead of on every screen
 * that happens to serialise a transaction.
 *
 * <p><strong>{@code providerResponse} is deliberately not on this record.</strong> §17.2
 * puts redaction in the adapter, so the column should hold nothing sensitive — and "should"
 * is the whole reason a bulk read does not return it. A log screen answers "what happened
 * and why was it refused", which is {@link #status()}, {@link #failureCode()} and
 * {@link #failureMessage()}; the raw document is what a support engineer reads out of the
 * database with a reason to, not what an endpoint hands to a browser twenty-five rows at a
 * time.
 *
 * <p><strong>The three closed vocabularies cross as strings, not as enums.</strong>
 * {@code TransactionType}, {@code TransactionStatus} and {@code ProviderName} are this
 * module's {@code domain} types, and §9.4 makes that boundary a rule rather than a
 * preference: a provider change must be a single-file change, which it cannot be if the
 * admin module holds a {@code ProviderName}. {@code PaymentProviderBoundaryTests} enforces
 * it, and it caught this record's first version — which handed all three across and let the
 * console call {@code .name()} on them. The conversion belongs here, inside the module that
 * owns the vocabulary, and the values are the enum names because that is what the column
 * holds and what every other closed set on this platform puts on the wire.
 *
 * @param id the transaction row, which is a UUID v7 and therefore its own position in
 *     arrival order (§7.3) — this is what the log's cursor is
 * @param pledgeId which pledge this was about, or null on a payout. V41's check pairs the
 *     two
 * @param projectId whose money this is. Present on every row, payouts included
 * @param type {@code CHARGE}, {@code VERIFICATION}, {@code REFUND}, {@code CHARGEBACK},
 *     {@code CHARGEBACK_REVERSAL} or {@code PAYOUT}
 * @param status what the provider said, frozen at insert — {@code SUCCEEDED},
 *     {@code FAILED} or {@code PENDING}. There is no path out of {@code PENDING}: a
 *     resolution is a new row, which is why {@link #attemptNumber()} and not the status is
 *     what tells one attempt from the next
 * @param amount always positive; direction is a property of {@link #type()}, not a sign
 * @param provider which adapter made the call, on the row rather than in configuration
 *     because the configuration will have changed by the time anybody asks
 * @param providerTransactionId the provider's own reference — the thing a support
 *     conversation and a dispute are both conducted in — or null when the call never got
 *     an answer
 * @param failureCode the provider's vocabulary for the refusal, or null
 * @param failureMessage the same refusal in words, or null
 * @param attemptNumber which of §9.6's four attempts this was, counted from one
 * @param createdAt when the row was written, from the database's clock
 */
public record LoggedTransaction(
        UUID id,
        UUID pledgeId,
        UUID projectId,
        String type,
        String status,
        Money amount,
        String provider,
        String providerTransactionId,
        String failureCode,
        String failureMessage,
        int attemptNumber,
        Instant createdAt) {

    static LoggedTransaction of(PaymentTransaction transaction) {
        return new LoggedTransaction(
                transaction.getId(),
                transaction.getPledgeId(),
                transaction.getProjectId(),
                transaction.getType().name(),
                transaction.getStatus().name(),
                transaction.getAmount(),
                transaction.getProvider().name(),
                transaction.getProviderTransactionId(),
                transaction.getFailureCode(),
                transaction.getFailureMessage(),
                transaction.getAttemptNumber(),
                transaction.getCreatedAt());
    }
}
