package az.ideanest.reward.application;

import java.util.List;
import java.util.UUID;

/**
 * An item that a reward tier contains cannot be deleted.
 *
 * <p>Answered as 409 with {@code code: ITEM_IN_USE} and the tiers in {@code meta}.
 *
 * <p><strong>Not in the contract's endpoint list, and something has to happen.</strong>
 * The alternative was a cascade, which would have removed the item from every tier
 * that included it — quietly changing what a backer was promised, in a request that
 * looks like housekeeping in a log. Refusing and naming the tiers means the creator
 * takes the item out of each one deliberately, which is the same number of clicks
 * and a decision rather than a side effect.
 *
 * <p>The database refuses it too:
 * {@code reward_tier_items_reference_an_item_of_the_same_project} is
 * {@code ON DELETE NO ACTION}. This is what makes the refusal a 409 the editor can
 * explain instead of a constraint violation at commit.
 */
public class ItemInUseException extends RuntimeException {

    private final List<UUID> rewardTierIds;

    public ItemInUseException(UUID itemId, List<UUID> rewardTierIds) {
        super("Item " + itemId + " is part of " + rewardTierIds.size() + " reward tiers");
        this.rewardTierIds = List.copyOf(rewardTierIds);
    }

    /** The tiers to remove it from first, so the client can link to them. */
    public List<UUID> rewardTierIds() {
        return rewardTierIds;
    }
}
