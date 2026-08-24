package az.ideanest.fee.application;

import az.ideanest.shared.money.Money;
import java.util.UUID;

/**
 * What a sum of money becomes once the platform and the processor have taken theirs —
 * §9, issue #311.
 *
 * <p><strong>Every component is returned, and the net is not left to the caller.</strong>
 * A method answering only "the fee" would be subtracted from the gross by each of its
 * callers, and the third one to do it would round differently. Here the arithmetic
 * happens once, in {@link FeeSchedules#priceOf}, and what comes back adds up by
 * construction — {@link #balances()} says so, and {@code FeeBreakdownTests} asserts it.
 *
 * @param gross what was collected
 * @param platformFee the platform's cut
 * @param processingFee the provider's cut, rate and fixed amount together. One number
 *     rather than two because nothing downstream distinguishes them: they go to the same
 *     ledger account and appear on a creator's statement as one line. Which schedule
 *     produced them is on the row this came from
 * @param net what is left for the creator
 * @param scheduleId the schedule that priced it, so that a payout can name what it was
 *     calculated under and be re-derived years later. Null only when no schedule is
 *     configured at all — see {@link FeeSchedules#priceOf} on why that is zero fees
 *     rather than a refusal
 */
public record FeeBreakdown(Money gross, Money platformFee, Money processingFee, Money net, UUID scheduleId) {

    /**
     * Whether the parts add up to the whole.
     *
     * <p>Not an assertion in the constructor, deliberately. A record that threw on an
     * unbalanced breakdown would turn a rounding defect into a 500 on a payout screen,
     * where what is wanted is a test that fails in CI. {@link FeeSchedules} is the only
     * thing that constructs one, and it is the thing under test.
     */
    public boolean balances() {
        return gross.equals(net.plus(platformFee).plus(processingFee));
    }

    /** Everything that was taken off. The number a creator asks about. */
    public Money totalFees() {
        return platformFee.plus(processingFee);
    }

    /**
     * The breakdown for a platform that has configured no schedule.
     *
     * <p>Zero fees rather than a refusal, and {@link FeeSchedules#priceOf} has the
     * argument: this fails towards paying a creator too much, which is recoverable, and
     * away from a payout run that stops with an exception, which is not.
     */
    public static FeeBreakdown free(Money gross) {
        Money zero = Money.zero(gross.currency());
        return new FeeBreakdown(gross, zero, zero, gross, null);
    }
}
