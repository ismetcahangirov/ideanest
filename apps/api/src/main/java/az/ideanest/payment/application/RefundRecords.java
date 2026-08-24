package az.ideanest.payment.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.ledger.application.Ledger;
import az.ideanest.ledger.application.LedgerAccount;
import az.ideanest.ledger.application.Posting;
import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.payment.domain.Refund;
import az.ideanest.payment.domain.RefundReason;
import az.ideanest.payment.domain.RefundResult;
import az.ideanest.payment.infrastructure.PaymentTransactionRepository;
import az.ideanest.payment.infrastructure.RefundRepository;
import az.ideanest.shared.money.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two database halves of a refund, each in its own transaction — #67.
 *
 * <h2>Why this is a separate bean and not three methods on {@code RefundService}</h2>
 *
 * <p>Because {@code @Transactional} is applied by a proxy, and a proxy is only in the way
 * when a call arrives from outside. {@code RefundService.issue} calling its own
 * {@code @Transactional} method would run it on {@code this}, the annotation would do
 * nothing at all, and the refund would be recorded with no transaction and no error —
 * which is the worst shape a defect can have on this code path, because it works in every
 * test that does not kill the process at the right moment.
 *
 * <p>So the boundary is a bean boundary. {@code RefundService} orchestrates and calls
 * across; the commits happen here.
 *
 * <p>Every method is package-private except by necessity: nothing outside this package has
 * any business writing a refund without going through the overdraft check and the
 * idempotency replay that {@code RefundService} performs around them.
 */
@Service
public class RefundRecords {

    private static final Logger log = LoggerFactory.getLogger(RefundRecords.class);

    private final RefundRepository refunds;
    private final PaymentTransactionRepository transactions;
    private final Ledger ledger;
    private final AuditLog audit;
    private final Clock clock;

    public RefundRecords(
            RefundRepository refunds,
            PaymentTransactionRepository transactions,
            Ledger ledger,
            AuditLog audit,
            Clock clock) {
        this.refunds = refunds;
        this.transactions = transactions;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Step one: the intent, committed before anybody calls a provider.
     *
     * <p>The overdraft check and the insert are in this transaction together. Two members
     * of staff each issuing a full refund in the same second would otherwise both read a
     * zero, and the platform would return twice what it took.
     *
     * @throws NothingToRefundException when the pledge has no settled charge
     * @throws RefundExceedsCollectionException when this would return more than was taken
     */
    @Transactional
    Refund record(
            UUID staffId, UUID pledgeId, Money amount, RefundReason reason, String detail, String idempotencyKey) {

        List<PaymentTransaction> charges = transactions.settledChargesOf(pledgeId);
        if (charges.isEmpty()) {
            throw new NothingToRefundException(pledgeId);
        }

        PaymentTransaction charge = charges.getFirst();
        String currency = charge.getAmount().currency();

        Money collected = Money.of(transactions.collectedOn(pledgeId), currency);
        Money alreadyRefunded = Money.of(refunds.refundedAgainst(pledgeId), currency);
        Money remaining = collected.minus(alreadyRefunded);

        Money requested = amount == null ? remaining : amount;
        if (!requested.isPositive() || requested.isGreaterThan(remaining)) {
            throw new RefundExceedsCollectionException(pledgeId, requested, remaining);
        }

        Refund refund = refunds.save(Refund.requested(
                pledgeId,
                charge.getProjectId(),
                charge.getId(),
                requested,
                requested.equals(collected),
                reason,
                detail,
                staffId,
                idempotencyKey));

        audit.record(
                AuditAction.REFUND_ISSUED,
                refund.id(),
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "pledge=%s; amount=%s; reason=%s; full=%s"
                        .formatted(pledgeId, requested, reason, refund.fullRefund()));

        return refund;
    }

    /**
     * Step three: the transaction row, the ledger posting and the state, in one commit.
     *
     * <p>All three or none. A {@code transactions} row without its posting is money that
     * moved and does not appear in the books; a posting without the row is the reverse.
     *
     * <p><strong>Escrow is credited and {@code refunds} is debited, and the fees are not
     * reversed.</strong> The processor keeps its fee on a refunded charge, and whether the
     * platform keeps its own is a policy question §9.7 does not settle — inventing it here
     * would mean a payment adapter deciding the platform's revenue. What the ledger says
     * instead is true and narrow: this much went back to a backer.
     */
    @Transactional
    Refund settleSuccess(Refund refund, PaymentTransaction charge, RefundResult result) {
        PaymentTransaction recorded = transactions.save(PaymentTransaction.refund(
                refund.pledgeId(),
                refund.projectId(),
                refund.amount(),
                charge.getProvider(),
                result,
                refund.idempotencyKey()));

        ledger.post(Posting.of(recorded.getId(), refund.projectId())
                .debit(LedgerAccount.REFUNDS, refund.amount())
                .credit(LedgerAccount.ESCROW, refund.amount())
                .build());

        Refund attached = refunds.findById(refund.id()).orElseThrow();
        attached.succeeded(recorded.getId(), clock.instant().truncatedTo(ChronoUnit.MICROS));

        log.info("Refund {} of {} settled on pledge {}", refund.id(), refund.amount(), refund.pledgeId());
        return refunds.save(attached);
    }

    /**
     * The other outcome.
     *
     * <p>No {@code transactions} row and no posting, deliberately: nothing moved. The
     * provider's refusal is on the refund row, which is where somebody looking for it will
     * be — a {@code FAILED} transaction row for a refund would appear on the payment log
     * beside real charges and would have to be filtered out of every sum.
     */
    @Transactional
    Refund settleFailure(Refund refund, String failureCode, String failureMessage) {
        Refund attached = refunds.findById(refund.id()).orElseThrow();
        attached.failed(failureCode, failureMessage, clock.instant().truncatedTo(ChronoUnit.MICROS));

        log.warn("Refund {} failed on pledge {}: {}", refund.id(), refund.pledgeId(), failureCode);
        return refunds.save(attached);
    }
}
