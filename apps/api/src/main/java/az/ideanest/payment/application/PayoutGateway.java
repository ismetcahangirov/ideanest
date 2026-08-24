package az.ideanest.payment.application;

import az.ideanest.payment.domain.PaymentProvider;
import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.payment.domain.PayoutRequest;
import az.ideanest.payment.domain.PayoutResult;
import az.ideanest.payment.domain.ProviderUnavailableException;
import az.ideanest.payment.infrastructure.PaymentTransactionRepository;
import az.ideanest.payment.infrastructure.RefundRepository;
import az.ideanest.shared.money.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment module's half of a payout — issue #69.
 *
 * <h2>Why the payout module does not do this itself</h2>
 *
 * <p>Because {@code transactions}, {@code refunds} and the provider adapters are this
 * module's, and {@code ModuleBoundaryTests} forbids the payout module from reaching into
 * them. That rule is doing real work here rather than being obeyed for its own sake: what
 * the payout module owns is a decision — what a creator is owed, whether the hold has
 * expired, whether two people have signed — and what this owns is the movement of money.
 * They change for different reasons, and the second changes whenever a provider does.
 *
 * <p>So {@code payout.application.PayoutService} names this and never a repository.
 */
@Service
public class PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(PayoutGateway.class);

    private final PaymentTransactionRepository transactions;
    private final RefundRepository refunds;
    private final PaymentProviders providers;
    private final PayoutPostings postings;

    public PayoutGateway(
            PaymentTransactionRepository transactions,
            RefundRepository refunds,
            PaymentProviders providers,
            PayoutPostings postings) {
        this.transactions = transactions;
        this.refunds = refunds;
        this.providers = providers;
        this.postings = postings;
    }

    /**
     * What a campaign has taken and given back.
     *
     * @param fallbackCurrency what to answer in when the campaign has no settled charge.
     *     A campaign with no charges has no currency of its own on this table, and
     *     {@link Money} has no currency-less zero — so the caller supplies the platform's
     *     rather than this method inventing one
     */
    @Transactional(readOnly = true)
    public CampaignFunds fundsOf(UUID projectId, String fallbackCurrency) {
        List<PaymentTransaction> charges = transactions.settledChargesOfProject(projectId);
        if (charges.isEmpty()) {
            return CampaignFunds.none(fallbackCurrency);
        }

        String currency = charges.getFirst().getAmount().currency();
        Money collected = Money.zero(currency);
        for (PaymentTransaction charge : charges) {
            collected = collected.plus(charge.getAmount());
        }

        return new CampaignFunds(collected, Money.of(refunds.refundedOnProject(projectId), currency));
    }

    /**
     * Sends the money, and records that it went.
     *
     * <p><strong>The provider call is outside a transaction and the writes are inside
     * one</strong> — the same three-commit shape {@code RefundService} uses, and for the
     * same reason: no arrangement of a database write, a call to somebody else's server,
     * and another database write is atomic, so the platform picks the failure that leaves
     * a record.
     *
     * @param idempotencyKey §9.3's R-08, and here it is the difference between paying a
     *     creator once and twice
     * @throws NoPayoutProviderException when no adapter is configured to send one
     */
    public Sent send(
            UUID payoutId,
            UUID projectId,
            UUID creatorId,
            Money amount,
            String destinationReference,
            String idempotencyKey) {

        Optional<PaymentTransaction> replayed = transactions.findByIdempotencyKey(idempotencyKey);
        if (replayed.isPresent()) {
            // The provider was already asked under this key. Returning the recorded row
            // rather than asking again is the whole of R-08 on the one endpoint where a
            // duplicate is a creator paid twice.
            PaymentTransaction existing = replayed.get();
            log.info("Payout {} replayed under key {}", payoutId, idempotencyKey);
            return new Sent(
                    existing.moved(), existing.getId(), existing.getFailureCode(), existing.getFailureMessage());
        }

        PaymentProvider provider = providers.primary().orElseThrow(NoPayoutProviderException::new);

        PayoutResult result;
        try {
            result = provider.payout(
                    new PayoutRequest(payoutId, creatorId, amount, destinationReference, idempotencyKey));
        } catch (ProviderUnavailableException e) {
            // No transaction row: the provider was never reached, so there is nothing it
            // said to record. The payout stays approved and is retried, which is safe
            // because the idempotency key is the same one.
            log.warn("Payout {} could not be sent: {}", payoutId, e.getMessage());
            return new Sent(false, null, PaymentTransaction.UNREACHABLE, e.getMessage());
        }

        return postings.record(payoutId, projectId, creatorId, amount, provider.name(), result, idempotencyKey);
    }

    /**
     * What became of the instruction.
     *
     * @param moved whether the provider accepted it. {@code PENDING} counts as accepted —
     *     {@code PayoutResult}'s comment notes that a bank transfer settles on a banking
     *     day, and the platform's obligation is discharged when the instruction is taken
     * @param transactionId the row that was written, null when the provider could not be
     *     reached at all
     */
    public record Sent(boolean moved, UUID transactionId, String failureCode, String failureMessage) {
    }
}
