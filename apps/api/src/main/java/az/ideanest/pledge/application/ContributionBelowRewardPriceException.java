package az.ideanest.pledge.application;

import az.ideanest.shared.money.Money;
import java.math.BigDecimal;

/**
 * The backer chose to give less than the reward they selected costs. §10.4's
 * {@code CONTRIBUTION_BELOW_REWARD_PRICE}.
 *
 * <p>§4.5's PL-03 is support <em>above</em> the tier price, so this can only be a
 * client working from a price that has since changed. A 422, for
 * {@link ShippingDestinationUnpricedException}'s reason: both numbers are perfectly
 * good money and it is the pair that is wrong.
 *
 * <p><strong>Refused rather than clamped.</strong> Charging the tier's price anyway
 * would take more than the number the backer was looking at when they pressed the
 * button, which is the one thing a checkout must never do quietly. Both amounts are
 * carried so the client can reload the tier and say what it now costs.
 */
public class ContributionBelowRewardPriceException extends RuntimeException {

    private final Money contribution;
    private final Money rewardPrice;

    public ContributionBelowRewardPriceException(BigDecimal contribution, BigDecimal rewardPrice, String currency) {
        super("A contribution of " + contribution + " is below the " + rewardPrice + " the selected reward costs");
        this.contribution = Money.of(contribution, currency);
        this.rewardPrice = Money.of(rewardPrice, currency);
    }

    /** What the backer offered. Money, so the problem detail carries it as a string like every other amount. */
    public Money contribution() {
        return contribution;
    }

    /** What the tier costs now. */
    public Money rewardPrice() {
        return rewardPrice;
    }
}
