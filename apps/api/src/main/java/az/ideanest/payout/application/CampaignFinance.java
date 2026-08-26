package az.ideanest.payout.application;

import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a campaign has taken, what came off it, and what is left — §4.7's CD-16, issue #99.
 *
 * <h2>Every figure, and never only the difference</h2>
 *
 * A creator asking "why was I paid this" is asking five questions, and a net figure answers
 * none of them. {@code CampaignFunds} makes the same argument one level down and V55 stores
 * both halves on the payout row for the same reason. So this carries the gross, each fee
 * separately, the tax, the refunds and the net — and the ledger balances underneath them, so
 * that the arithmetic is checkable rather than asserted.
 *
 * <h2>PROJECTED AND SETTLED ARE DIFFERENT ANSWERS AND ARE LABELLED AS SUCH</h2>
 *
 * Before a payout is calculated there is no fee that has been charged: there is a fee
 * schedule, and what it would price today. That is worth showing — a creator planning a
 * campaign needs it — and it is not the same statement as "this is what you were paid", and a
 * screen that presented them identically would be lying on one of the two days it matters.
 * {@link Basis} is which one this is, and it is a field rather than a footnote.
 *
 * <p>The projection can also become wrong without anything being wrong: §5.2's schedule is
 * versioned and a payout prices against the one in force at the moment it is calculated, so a
 * campaign that runs across a rate change is quoted at today's rate and paid at that day's.
 * {@link #feeScheduleId} is on the record either way, which is what makes the difference
 * explicable rather than a discrepancy.
 *
 * <h2>Tax is zero, and that is a fact about the platform rather than about this campaign</h2>
 *
 * §4.10's tax collection is #78 and is blocked on a legal answer, so {@code pledges.tax_amount}
 * is zero on every row and {@code tax_payable} is an account nothing credits. Reporting zero is
 * therefore true, and reporting it with no explanation would read as "no tax is due on your
 * earnings", which is not something this platform is in a position to say. {@link #taxWithheld}
 * is the number and {@link #taxCollected} is whether the platform collects any at all.
 *
 * @param projectId the campaign
 * @param basis whether these figures are what a payout would be or what one was
 * @param currency the one currency this campaign holds. §21.2 refuses to add two
 * @param gross what settled charges came to. From {@code transactions} rather than from
 *     {@code pledges.amount}, because what can be paid out is what was taken and the two differ
 *     whenever a collection was partial
 * @param refunded what has gone back, including refunds requested and not yet sent
 * @param platformFee §5.2's platform fee
 * @param processingFee §5.2's provider fee
 * @param taxWithheld always zero today. See above
 * @param taxCollected whether the platform withholds any tax at all, so that a client can say
 *     which kind of zero it is showing
 * @param net what is payable, or was paid: fees off the gross, then refunds off what is left.
 *     Doing it the other way round would take a platform fee on money that went back to a
 *     backer, and {@code PayoutService} makes the same choice at the moment it matters
 * @param paidOut the sum of every payout that reached {@code PAID}
 * @param feeScheduleId which version of §5.2's rates these fees came from
 * @param payouts every payout this campaign has had, newest first, whatever became of it
 * @param ledger what the books say, per account, for this campaign
 * @param reconciled whether {@link #net} agrees with the ledger. See
 *     {@link CampaignFinanceService} for what a false means and what it does not
 * @param computedAt when this was assembled
 */
public record CampaignFinance(
        UUID projectId,
        Basis basis,
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
        List<PayoutSummary> payouts,
        List<AccountBalance> ledger,
        boolean reconciled,
        Instant computedAt) {

    /** Whether these figures describe what would happen or what did. */
    public enum Basis {
        /** No payout exists yet. The fees are what §5.2's current schedule would charge. */
        PROJECTED,
        /** A payout exists. The fees are the ones it was priced at. */
        SETTLED
    }

    /**
     * One payout, as much of it as a creator is entitled to see.
     *
     * <p>Deliberately not the whole row. {@code Payout} carries the approvals it needs, the
     * staff who gave them and the provider's transaction identifier; those belong to §4.11's
     * console and to the audit trail, and a creator does not need to know how many signatures
     * their money took to see that it is on its way.
     */
    public record PayoutSummary(UUID id, String state, Money net, Instant calculatedAt, Instant sentAt) {}

    /**
     * One ledger account's position for this campaign.
     *
     * <p>Signed the way §7.2's ledger is signed: debits positive, credits negative. It is
     * published rather than summarised because a summary the creator cannot check against
     * anything is a number they have to take on trust, and this is the one screen where that
     * is not good enough.
     */
    public record AccountBalance(String account, Money net) {}
}
