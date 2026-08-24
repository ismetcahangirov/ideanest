package az.ideanest.payment.application;

import az.ideanest.ledger.application.Ledger;
import az.ideanest.ledger.application.LedgerAccount;
import az.ideanest.ledger.application.Posting;
import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.payment.domain.PayoutResult;
import az.ideanest.payment.domain.ProviderName;
import az.ideanest.payment.domain.ProviderOutcome;
import az.ideanest.payment.infrastructure.PaymentTransactionRepository;
import az.ideanest.shared.money.Money;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of sending a payout, in one commit — issue #69.
 *
 * <p>A separate bean for {@code RefundRecords}' reason, which is worth repeating because
 * it is the kind of defect that passes every test: {@code @Transactional} is applied by a
 * proxy, so a {@code @Transactional} method called from another method of the same class
 * runs with no transaction at all and reports nothing. {@link PayoutGateway} calls across
 * a bean boundary so that the annotation is in the path.
 */
@Service
public class PayoutPostings {

    private static final Logger log = LoggerFactory.getLogger(PayoutPostings.class);

    private final PaymentTransactionRepository transactions;
    private final Ledger ledger;

    public PayoutPostings(PaymentTransactionRepository transactions, Ledger ledger) {
        this.transactions = transactions;
        this.ledger = ledger;
    }

    /**
     * The transaction row and, when money moved, the ledger posting.
     *
     * <p>A declined payout gets a {@code FAILED} transaction row and <strong>no
     * posting</strong>: nothing moved. The row exists because a provider that refused is a
     * fact worth keeping — a reconciliation against the provider's statement needs it, and
     * so does the creator asking why they have not been paid.
     *
     * <p>The posting is the counterpart of the collection's. A collection debits escrow and
     * credits the creator's account; this debits the creator's account and credits escrow,
     * discharging the claim as the money leaves. <strong>The fees are not touched</strong>
     * — they were taken at collection, and taking them again here would charge twice.
     */
    @Transactional
    PayoutGateway.Sent record(
            UUID payoutId,
            UUID projectId,
            UUID creatorId,
            Money amount,
            ProviderName provider,
            PayoutResult result,
            String idempotencyKey) {

        PaymentTransaction recorded =
                transactions.save(PaymentTransaction.payout(projectId, amount, provider, result, idempotencyKey));

        if (result.outcome() == ProviderOutcome.DECLINED) {
            log.warn("Payout {} declined by {}: {}", payoutId, provider, result.failureCode());
            return new PayoutGateway.Sent(
                    false, recorded.getId(), result.failureCode(), result.failureMessage());
        }

        ledger.post(Posting.of(recorded.getId(), projectId)
                .debit(LedgerAccount.creator(creatorId), amount)
                .credit(LedgerAccount.ESCROW, amount)
                .build());

        log.info("Payout {} of {} sent for campaign {}", payoutId, amount, projectId);
        return new PayoutGateway.Sent(true, recorded.getId(), null, null);
    }
}
