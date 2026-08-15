package az.ideanest.reward.application;

import az.ideanest.reward.domain.Item;
import az.ideanest.reward.domain.RewardTierItem;
import az.ideanest.reward.infrastructure.ItemRepository;
import az.ideanest.reward.infrastructure.RewardTierItemRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The atomic units a campaign produces: writing them down, correcting them, and
 * removing the ones that turned out not to exist.
 *
 * <p><strong>Validation is here as well as in the database.</strong> Every rule
 * below is also a check constraint in {@code V7}. The constraint holds against a
 * support query, an import, and a code path nobody has written yet; this is what
 * turns the same rule into a 400 naming the field, because a constraint violation
 * reaches the client as a 500 and tells them nothing they can act on.
 *
 * <p><strong>What is not here.</strong> Nothing about which state the campaign is in.
 * §5.3 freezes a campaign's goal and deadline and a reward's price after launch, and
 * {@code ProjectEditLocks} is the one table that says so; an item is none of those.
 * An item is a thing the campaign produces rather than a promise made to a backer —
 * what a backer chose is a tier, at a price, containing a quantity of items — so
 * correcting an item's name or its weight after launch is bookkeeping, not a change
 * to the offer. The tier's composition is where that offer is defined, and it is
 * {@code RewardService} that guards it.
 */
@Service
public class ItemService {

    /** Also {@code items_name_length}. Long enough for "Hardcover, signed by the illustrator". */
    private static final int NAME_MAX = 120;

    /** Also {@code items_sku_length}. A warehouse code, not a description. */
    private static final int SKU_MAX = 64;

    private final ItemRepository items;
    private final RewardTierItemRepository compositions;
    private final RewardAccess access;

    public ItemService(ItemRepository items, RewardTierItemRepository compositions, RewardAccess access) {
        this.items = items;
        this.compositions = compositions;
        this.access = access;
    }

    /** Every item of the campaign, for its creator. Anybody else is told the campaign does not exist. */
    @Transactional(readOnly = true)
    public List<Item> list(UUID projectId, UUID accountId) {
        access.requireEditableProject(projectId, accountId);
        return items.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    @Transactional
    public Item create(UUID projectId, UUID accountId, NewItem draft) {
        access.requireEditableProject(projectId, accountId);

        Item item = Item.of(projectId, requireName(draft.name()));
        item.setDescription(blankAsNull(draft.description()));
        item.setImageUrl(blankAsNull(draft.imageUrl()));
        item.setSku(uniqueSku(projectId, null, draft.sku()));
        applyPhysicality(item, draft.digital(), draft.weightGrams());

        return items.save(item);
    }

    /**
     * Applies a partial edit.
     *
     * <p>Field by field, and only the fields the client mentioned — except for the
     * two that describe what the item physically is, which move together because the
     * database refuses a digital item with a weight.
     */
    @Transactional
    public Item edit(UUID itemId, UUID accountId, ItemPatch patch) {
        Item item = access.requireEditableItem(itemId, accountId);

        patch.name().ifPresent(name -> item.setName(requireName(name)));
        patch.description().ifPresent(description -> item.setDescription(blankAsNull(description)));
        patch.imageUrl().ifPresent(url -> item.setImageUrl(blankAsNull(url)));
        patch.sku().ifPresent(sku -> item.setSku(uniqueSku(item.getProjectId(), itemId, sku)));

        if (patch.changesPhysicality()) {
            boolean digital = patch.digital().isPresent()
                    ? requireBoolean("digital", patch.digital().value())
                    : item.isDigital();
            Integer weight = patch.weightGrams().isPresent()
                    ? patch.weightGrams().value()
                    : item.getWeightGrams();
            applyPhysicality(item, digital, weight);
        }

        return item;
    }

    /**
     * Removes an item, unless a reward tier contains it.
     *
     * <p>Refused rather than cascaded. Removing the item from every tier that
     * included it would change what a backer was promised, in a request that reads as
     * housekeeping; naming the tiers means the creator takes it out of each one
     * deliberately. The foreign key refuses the same delete, which is what holds when
     * this check is not the code doing the deleting.
     */
    @Transactional
    public void delete(UUID itemId, UUID accountId) {
        Item item = access.requireEditableItem(itemId, accountId);

        List<UUID> usedBy = compositions.findByItem(itemId).stream()
                .map(RewardTierItem::getRewardTierId)
                .toList();
        if (!usedBy.isEmpty()) {
            throw new ItemInUseException(itemId, usedBy);
        }

        items.delete(item);
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            // Not clearable: the column is NOT NULL, and an item with no name cannot
            // be listed in the tier editor or totalled in a fulfilment report. A
            // client sending null here is trying to clear a required field, which is
            // a different mistake from omitting it.
            throw new RewardFieldRejectedException("name", "An item needs a name.");
        }
        if (trimmed.length() > NAME_MAX) {
            throw new RewardFieldRejectedException("name", "That is longer than " + NAME_MAX + " characters.");
        }
        return trimmed;
    }

    /**
     * The code, if nothing else in the campaign is already using it.
     *
     * @param itemId the item being edited, or null when one is being created. Passed
     *     so that saving a form without changing the code is not a collision with
     *     itself
     */
    private String uniqueSku(UUID projectId, UUID itemId, String sku) {
        String normalised = blankAsNull(sku);
        if (normalised == null) {
            return null;
        }
        if (normalised.length() > SKU_MAX) {
            throw new RewardFieldRejectedException("sku", "A stock code is at most " + SKU_MAX + " characters.");
        }

        Optional<Item> existing = items.findByProjectIdAndSku(projectId, normalised);
        if (existing.isPresent() && !existing.get().getId().equals(itemId)) {
            throw new RewardFieldRejectedException(
                    "sku", "Another item in this campaign already uses that stock code.");
        }
        return normalised;
    }

    /**
     * What the item is, and what it weighs.
     *
     * <p>The entity refuses the impossible combination with an
     * {@link IllegalArgumentException}, which is right for a caller that has no user
     * to answer; here there is one, and they get the field name.
     */
    private static void applyPhysicality(Item item, boolean digital, Integer weightGrams) {
        if (weightGrams != null && weightGrams <= 0) {
            throw new RewardFieldRejectedException("weightGrams", "A weight is more than zero grams.");
        }
        if (digital && weightGrams != null) {
            throw new RewardFieldRejectedException(
                    "weightGrams", "A digital item has no shipping weight. Clear the weight, or make it physical.");
        }
        item.describePhysicality(digital, weightGrams);
    }

    /**
     * Treats an empty string as an absent value.
     *
     * <p>A creator who empties a text input sends {@code ""}, not {@code null}. The
     * database refuses a blank description — a zero-length string is not a
     * description — and answering an autosave with a 400 for clearing a field is a
     * worse experience than storing what the creator plainly meant.
     */
    private static String blankAsNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean requireBoolean(String field, Boolean value) {
        if (value == null) {
            throw new RewardFieldRejectedException(field, "That setting is either on or off.");
        }
        return value;
    }
}
