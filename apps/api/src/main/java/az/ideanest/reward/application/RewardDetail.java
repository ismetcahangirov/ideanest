package az.ideanest.reward.application;

import az.ideanest.reward.domain.RewardTier;
import az.ideanest.reward.domain.RewardTierItem;
import az.ideanest.reward.domain.ShippingRule;
import java.util.List;

/**
 * A reward tier with everything that hangs off it.
 *
 * <p>Composition and shipping rates are separate tables and separate entities —
 * see {@code RewardTier} for why they are not mapped as associations — so something
 * has to carry the three together from the service to the response. This does, and
 * it is assembled with one query per collection for a whole reward list rather than
 * one per tier.
 *
 * <p>Every endpoint in this module that returns a tier returns this, so a client
 * applies the same update to its state after a create, a patch, a duplicate, and a
 * shipping-rate replacement. An endpoint that answered with a thinner shape would
 * make a client's state depend on which request it happened to make last.
 */
public record RewardDetail(RewardTier tier, List<RewardTierItem> contents, List<ShippingRule> shippingRules) {

    public RewardDetail {
        contents = List.copyOf(contents);
        shippingRules = List.copyOf(shippingRules);
    }
}
