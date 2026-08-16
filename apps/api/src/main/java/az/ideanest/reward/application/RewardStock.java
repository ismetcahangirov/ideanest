package az.ideanest.reward.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * What the pledge module needs from a reward tier: its price, and one place on it.
 *
 * <p>The seam reservation (#51) reserves through. It is here rather than in
 * {@code reward.domain} or {@code reward.infrastructure} because those are this
 * module's internals and {@code ModuleBoundaryTests} refuses another module a view
 * of them — the application layer is the only part this module has agreed to keep
 * stable.
 *
 * <p><strong>Declared by the provider, unlike
 * {@code project.application.RewardFacts}</strong>, and the difference is not
 * taste. That interface is declared by the module that needs the answer because
 * the alternative would be a cycle. Here the alternative <em>is</em> the cycle:
 * {@code pledge.domain.QuotedLine} already names {@code ShippingRate} from this
 * package (#53), so the pledge module depends on this one, and an interface
 * declared over there and implemented here would close the loop —
 * {@code ModuleBoundaryTests} catches it in one line. One direction between two
 * modules, and this is the direction that already exists.
 *
 * <p><strong>Why the increment is not done here.</strong> §7.2 puts the stock
 * columns on {@code reward_tiers} and says they are "written by the pledge module
 * and by reservation, never by the campaign editor". Written by, not owned by: the
 * table belongs to the reward module, and a second module issuing statements
 * against it would be two modules sharing a table with one of them unable to see
 * the other's constraints. The seam is three methods wide instead, and each of
 * them is one statement.
 *
 * <p><strong>Two calls rather than one, deliberately.</strong> {@link #priceOf}
 * resolves the tier and {@link #reserveOnePlace} takes a place, and the split is
 * what lets a caller tell "there is no such tier on this campaign" — which is a
 * client that asked for the wrong thing — from "there is, and it is full", which
 * is §10.4's {@code REWARD_SOLD_OUT} and a different answer with different
 * alternatives in it. Collapsing them into one call that returns an empty result
 * for both would make those two indistinguishable at the only place that can tell
 * the backer which happened.
 */
public interface RewardStock {

    /**
     * What a place on this tier costs, if the tier is part of this campaign.
     *
     * <p>The campaign is part of the question rather than a check the caller makes
     * afterwards. A pledge names both, and a tier belonging to a different
     * campaign is not a tier this pledge can hold — the composite foreign key in
     * V17 refuses the row, and this is what makes the refusal answerable.
     *
     * @return empty when there is no such tier on that campaign
     */
    Optional<RewardTierPrice> priceOf(UUID projectId, UUID rewardTierId);

    /**
     * Takes one place on the tier, if there is one left.
     *
     * <p>One conditional {@code UPDATE}, which is the whole mechanism. The
     * statement takes PostgreSQL's row lock, re-reads the counts behind it, and
     * refuses itself when the tier is full — so two checkouts racing for the last
     * place are serialised by the database rather than by a check in Java that was
     * true when it ran. V7's {@code reward_tiers_stock_is_within_the_limit} is the
     * second line: if this statement is ever wrong, the transaction is refused
     * rather than the reward oversold.
     *
     * <p>A tier with no limit is unlimited and always has a place. The count is
     * still incremented, because §5.3 lets a creator add a limit later and the
     * floor it may be lowered to is the places already taken.
     *
     * @return false when the tier is full, or has gone since {@link #priceOf} saw
     *     it — both of which are "there is no place for this backer", which is
     *     what the caller has to tell them
     */
    boolean reserveOnePlace(UUID rewardTierId);

    /**
     * Gives one place back, when a reservation lapses or a draft is abandoned.
     *
     * <p>Guarded against going below zero rather than trusted: the count is what
     * stands between a limited tier and being oversold, and a release that ran
     * twice would create a place that does not exist. The constraint would refuse
     * a negative count, but the damage of a double release is done above zero,
     * where it is silent.
     *
     * @return false when there was nothing to give back
     */
    boolean releaseOnePlace(UUID rewardTierId);

    /**
     * A tier's price, which is an amount and the currency it is in.
     *
     * @param amount {@code numeric(14,2)}, never a float — this is the number a
     *     card is charged
     */
    record RewardTierPrice(UUID rewardTierId, BigDecimal amount, String currency) {
    }
}
