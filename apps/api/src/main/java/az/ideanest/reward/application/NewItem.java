package az.ideanest.reward.application;

/**
 * An item as the creator first describes it.
 *
 * <p>Plain values rather than {@code Patched} ones, unlike {@link ItemPatch}:
 * creation has nothing to leave alone, so the distinction between "not mentioned"
 * and "explicitly empty" carries no information here. A create request that omits
 * the weight means the same thing as one that sends null for it.
 *
 * @param digital whether the item is delivered as a file. Its default is false,
 *     which is what an omitted flag means: most items are objects, and a creator
 *     who is shipping something has said so by giving it a weight
 */
public record NewItem(
        String name, String description, String imageUrl, Integer weightGrams, boolean digital, String sku) {
}
