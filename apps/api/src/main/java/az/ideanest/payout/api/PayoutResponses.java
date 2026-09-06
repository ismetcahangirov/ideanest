package az.ideanest.payout.api;

import az.ideanest.payout.application.PayoutService;
import az.ideanest.payout.domain.Payout;
import az.ideanest.payout.domain.PayoutApproval;
import az.ideanest.payout.domain.PayoutState;
import az.ideanest.shared.compliance.DestinationStanding;
import az.ideanest.shared.compliance.VerificationStanding;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            Instant sentAt,
            VerificationStanding creatorStanding,
            boolean heldForVerification,
            DestinationStanding destinationStanding,
            boolean heldForDestination) {

        /**
         * @param standing where the creator stands with identity verification (#431). Drawn on
         *     AD-05 rather than left for the operator to infer: a payout that will not approve
         *     and does not say why "makes the same campaign look as though nothing is owed"
         * @param destination where the creator stands with a payout destination (#432). A
         *     second value beside the first rather than one merged "held" flag, because the two
         *     are chased differently: an identity standing waits on a document queue, and a
         *     destination standing waits on a creator filing an account or a reviewer looking
         *     at one. An operator shown a single flag would not know which screen to open
         */
        public static PayoutSummary of(
                Payout payout, Instant now, VerificationStanding standing, DestinationStanding destination) {
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
                    payout.sentAt(),
                    standing,
                    !standing.releasesPayout() && payout.state().isInFlight(),
                    destination,
                    !destination.releasesPayout() && payout.state().isInFlight());
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

        public static PayoutFile of(
                PayoutService.PayoutFile file,
                Instant now,
                VerificationStanding standing,
                DestinationStanding destination) {
            return new PayoutFile(
                    PayoutSummary.of(file.payout(), now, standing, destination),
                    file.approvals().stream().map(Approval::of).toList(),
                    file.stillNeeded());
        }
    }

    /** A page of payouts. */
    public record PayoutPage(List<PayoutSummary> payouts, int page, boolean hasMore) {

        /**
         * @param standings one entry per distinct creator on the page (#431). A map rather than
         *     a lookup per row, because a page is fifty payouts and a handful of creators
         * @param destinations the same, for #432's destination standing. Two maps rather than
         *     one of pairs, because the two are read from different modules and a page where
         *     one of them was cheap should not pay for the other
         */
        public static PayoutPage of(
                List<Payout> payouts,
                int page,
                int size,
                Instant now,
                Map<UUID, VerificationStanding> standings,
                Map<UUID, DestinationStanding> destinations) {
            return new PayoutPage(
                    payouts.stream()
                            .map(payout -> PayoutSummary.of(
                                    payout,
                                    now,
                                    standings.getOrDefault(payout.creatorId(), VerificationStanding.NOT_REQUIRED),
                                    destinations.getOrDefault(payout.creatorId(), DestinationStanding.NONE)))
                            .toList(),
                    page,
                    payouts.size() == size);
        }
    }
}
