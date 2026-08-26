package az.ideanest.payout.api;

import az.ideanest.payout.application.CampaignFinance;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §4.7's CD-16 on the wire — issue #99.
 *
 * <p>Every amount is a {@link Money}, which serialises as a string with its currency per §10.3.
 * CLAUDE.md states the rule and the frontend's {@code decimal.js} is the other half of it: a
 * JSON number is a double, and a double is where a pledge's last qapik goes.
 *
 * <p><strong>The whole breakdown travels, not the net.</strong> This is the screen a creator
 * looks at to answer "why was I paid this", and a single figure with a note saying "fees
 * deducted" is not something anybody can check. The five numbers add up in front of the reader,
 * and the ledger balances are underneath them so that the sum itself is checkable.
 *
 * @param basis {@code PROJECTED} before any payout has been calculated, {@code SETTLED}
 *     afterwards. A client must say which it is showing: "this is what you would be paid" and
 *     "this is what you were paid" are different sentences, and the same numbers under both
 *     would be a lie on one of the two days it matters
 * @param taxCollected whether this platform withholds any tax at all. Always false today —
 *     §4.10 is #78 and is blocked on a legal answer — and it is a field rather than an omission
 *     so that a client can distinguish "no tax was due" from "we do not withhold any", which
 *     are different things to say to somebody who has to file a return
 * @param reconciled whether this campaign's ledger entries balance, summed per currency. See
 *     {@code CampaignFinanceService} for what a false means, and for the two accounts that
 *     currently have no writer at all
 */
public record CampaignFinanceResponse(
        UUID projectId,
        CampaignFinance.Basis basis,
        String currency,
        Money gross,
        Money refunded,
        Money platformFee,
        Money processingFee,
        Money taxWithheld,
        boolean taxCollected,
        Money net,
        Money paidOut,
        UUID feeScheduleId,
        List<PayoutRecord> payouts,
        List<LedgerBalanceRecord> ledger,
        boolean reconciled,
        Instant computedAt) {

    /**
     * One payout, as much of it as the campaign's team is entitled to see.
     *
     * <p>Deliberately narrower than {@link PayoutResponses.PayoutSummary}, which is §4.11's
     * console view: that one carries how many approvals the payout needs and the provider's
     * transaction identifier, and a creator does not need to know how many signatures their
     * money took to see that it is on its way.
     */
    public record PayoutRecord(UUID id, String state, Money net, Instant calculatedAt, Instant sentAt) {

        static PayoutRecord of(CampaignFinance.PayoutSummary summary) {
            return new PayoutRecord(
                    summary.id(), summary.state(), summary.net(), summary.calculatedAt(), summary.sentAt());
        }
    }

    /**
     * One of §7.2's accounts, as it stands for this campaign.
     *
     * <p>Signed the way the ledger is signed: debits positive, credits negative. A negative
     * balance on the creator's account is money the platform holds on their behalf, which is
     * the one row on this list a creator is likely to look for.
     */
    public record LedgerBalanceRecord(String account, Money net) {

        static LedgerBalanceRecord of(CampaignFinance.AccountBalance balance) {
            return new LedgerBalanceRecord(balance.account(), balance.net());
        }
    }

    public static CampaignFinanceResponse of(CampaignFinance finance) {
        return new CampaignFinanceResponse(
                finance.projectId(),
                finance.basis(),
                finance.currency(),
                finance.gross(),
                finance.refunded(),
                finance.platformFee(),
                finance.processingFee(),
                finance.taxWithheld(),
                finance.taxCollected(),
                finance.net(),
                finance.paidOut(),
                finance.feeScheduleId(),
                finance.payouts().stream().map(PayoutRecord::of).toList(),
                finance.ledger().stream().map(LedgerBalanceRecord::of).toList(),
                finance.reconciled(),
                finance.computedAt());
    }
}
