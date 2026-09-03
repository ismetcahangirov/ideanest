package az.ideanest.admin.api;

import az.ideanest.payment.application.LoggedTransaction;
import az.ideanest.payment.application.PaymentLogPage;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AD-05's payment log on the wire — #304.
 *
 * <p>The amount is a {@link Money}, which §10.3 serialises as a string and never as a JSON
 * number. On this surface that rule is not a formality: a payment log is read next to a
 * provider's own statement, and a figure that has been through an IEEE 754 double is a
 * figure that will eventually disagree with it by a qapik nobody can account for.
 *
 * <p><strong>The type, the status and the provider arrive here as strings.</strong> They are
 * closed sets, and they are the payment module's own — §9.4 says a provider change must be a
 * single-file change, which it cannot be if this file names a {@code ProviderName}. This
 * class passes them through rather than converting them, because the conversion happened in
 * {@link LoggedTransaction}, inside the module that owns the vocabulary.
 *
 * <p><strong>{@code LogPage} rather than {@code Page} since #404.</strong> The other half of
 * a schema-name collision {@link AuditTrailResponses} describes: two nested records called
 * {@code Page}, one generated schema, and one of the two endpoints documenting the other's
 * body. Both are named for what they are now.
 */
final class PaymentLogResponses {

    private PaymentLogResponses() {
    }

    static LogPage of(PaymentLogPage page) {
        return new LogPage(
                page.scope().pledgeId(),
                page.scope().projectId(),
                page.scope().status(),
                page.transactions().stream().map(PaymentLogResponses::transaction).toList(),
                page.nextCursor());
    }

    private static Transaction transaction(LoggedTransaction row) {
        return new Transaction(
                row.id(),
                row.pledgeId(),
                row.projectId(),
                row.type(),
                row.status(),
                row.amount(),
                row.provider(),
                row.providerTransactionId(),
                row.failureCode(),
                row.failureMessage(),
                row.attemptNumber(),
                row.createdAt());
    }

    /**
     * One page of the log, newest first.
     *
     * @param pledgeId which pledge was asked about, echoed and absent when none was.
     *     Echoed because the service resolves a request that names both a pledge and a
     *     campaign down to the pledge — see {@code PaymentLogScope} — and this is how a
     *     client learns that happened
     * @param projectId which campaign was asked about, echoed for the same reason
     * @param status which outcome was asked about, echoed and absent when none was — #404.
     *     Echoed in the spelling the column uses rather than the one the query carried, so a
     *     client that sent {@code ?status=failed} can see that {@code FAILED} is what was
     *     applied. Combines with the two above rather than replacing them, so all three can be
     *     present at once
     * @param transactions the matching provider calls, newest first
     * @param nextCursor what to send as {@code after} for the next page, or absent when
     *     this was the last one
     */
    record LogPage(
            UUID pledgeId, UUID projectId, String status, List<Transaction> transactions, UUID nextCursor) {
    }

    /**
     * One call to a provider, and what it said.
     *
     * @param id the transaction row, and this log's cursor
     * @param pledgeId which pledge this was about, absent on a payout
     * @param projectId whose money it is. Present on every row
     * @param type {@code CHARGE}, {@code VERIFICATION}, {@code REFUND}, {@code CHARGEBACK},
     *     {@code CHARGEBACK_REVERSAL} or {@code PAYOUT}. Only charges are written today;
     *     V41's check accepts the rest so that adding one is not a migration over the
     *     largest financial table the platform holds
     * @param status {@code SUCCEEDED}, {@code FAILED}, or {@code PENDING} when the provider
     *     accepted the instruction and had not decided. <strong>A status never moves.</strong>
     *     A pending call that later resolves is a second row, which is why two rows can
     *     share a provider reference and why {@link #attemptNumber()} is what tells one
     *     attempt from the next
     * @param amount always positive. Direction is a property of {@link #type()}, never a
     *     sign — a refund is a {@code REFUND} for a positive amount
     * @param provider which adapter made the call, on the row rather than in configuration
     *     because the configuration will have changed by the time anybody asks
     * @param providerTransactionId the provider's own reference, which is the identifier a
     *     support conversation and a dispute are both conducted in. Absent when the call
     *     never got an answer — a request that timed out is still a row, because it may
     *     have charged somebody
     * @param failureCode the provider's vocabulary for a refusal, absent on anything that
     *     did not fail
     * @param failureMessage the same refusal in words
     * @param attemptNumber which of §9.6's four attempts this was, counted from one
     * @param createdAt when the row was written
     */
    record Transaction(
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
    }
}
