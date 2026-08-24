package az.ideanest.payout.api;

import az.ideanest.payout.application.PayoutService;
import az.ideanest.payout.domain.Payout;
import az.ideanest.payout.domain.PayoutApproval;
import az.ideanest.payout.domain.PayoutState;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AD-05's payout queue, as the service describes it — issues #69 and #306.
 */
public final class PayoutResponses {

    private PayoutResponses() {
    }

    /**
     * One payout, with every figure that produced it.
     *
     * <p><strong>The whole breakdown travels, not just the net.</strong> This is the
     * screen where somebody signs off money leaving the platform, and a single figure with
     * a note saying "fees deducted" is not something anybody can check. The five numbers
     * add up in front of the reader.
     *
     * <p>Every amount is a {@link Money}, which serialises as a string with its currency
     * per §10.3 — CLAUDE.md, and the reason the frontend uses {@code decimal.js}.
     *
     * @param payableNow whether the hold has expired, computed against the server's clock.
     *     Sent rather than left to the browser: a client comparing {@code payableAt} to its
     *     own clock would show a payout as approvable a few seconds early or late, and the
     *     service would then refuse a button that looked enabled
     * @param feeScheduleId which terms produced the deductions, so the arithmetic can be
     *     traced back. Null only for a payout priced when no schedule was configured
     */
    public record PayoutSummary(
            UUID id,
            UUID projectId,
            UUID creatorId,
            Money gross,
            Money platformFee,
            Money processingFee,
            Money taxWithheld,
            Money refunded,
            Money net,
            UUID feeScheduleId,
            PayoutState state,
            Instant payableAt,
            boolean payableNow,
            short approvalsRequired,
            UUID payoutTransactionId,
            String failureCode,
            String failureMessage,
            Instant calculatedAt,
            Instant sentAt) {

        public static PayoutSummary of(Payout payout, Instant now) {
            return new PayoutSummary(
                    payout.id(),
                    payout.projectId(),
                    payout.creatorId(),
                    payout.gross(),
                    payout.platformFee(),
                    payout.processingFee(),
                    payout.taxWithheld(),
                    payout.refunded(),
                    payout.net(),
                    payout.feeScheduleId(),
                    payout.state(),
                    payout.payableAt(),
                    payout.isPayableAt(now),
                    payout.approvalsRequired(),
                    payout.payoutTransactionId(),
                    payout.failureCode(),
                    payout.failureMessage(),
                    payout.calculatedAt(),
                    payout.sentAt());
        }
    }

    /** One signature. */
    public record Approval(UUID approverId, Instant approvedAt, String note) {

        public static Approval of(PayoutApproval approval) {
            return new Approval(approval.approverId(), approval.approvedAt(), approval.note());
        }
    }

    /**
     * A payout and who has signed it.
     *
     * @param stillNeeded how many more signatures it needs. Sent rather than derived,
     *     because deriving it means the browser holding V55's rule about who may sign — and
     *     the browser cannot see that two rows are two different people
     */
    public record PayoutFile(PayoutSummary payout, List<Approval> approvals, long stillNeeded) {

        public static PayoutFile of(PayoutService.PayoutFile file, Instant now) {
            return new PayoutFile(
                    PayoutSummary.of(file.payout(), now),
                    file.approvals().stream().map(Approval::of).toList(),
                    file.stillNeeded());
        }
    }

    /** A page of payouts. */
    public record PayoutPage(List<PayoutSummary> payouts, int page, boolean hasMore) {

        public static PayoutPage of(List<Payout> payouts, int page, int size, Instant now) {
            return new PayoutPage(
                    payouts.stream().map(payout -> PayoutSummary.of(payout, now)).toList(),
                    page,
                    payouts.size() == size);
        }
    }
}
