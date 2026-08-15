package az.ideanest.reward.application;

import java.util.UUID;

/**
 * No such item, or none this caller is allowed to know about.
 *
 * <p>The same single answer as {@link RewardNotFoundException}, for the same
 * reason: an item is the description of an unreleased product.
 */
public class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(UUID itemId) {
        super("No item " + itemId + " is visible to this caller");
    }
}
