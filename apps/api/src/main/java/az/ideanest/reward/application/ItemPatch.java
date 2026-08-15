package az.ideanest.reward.application;

import az.ideanest.shared.Patched;

/**
 * A partial edit of an item, with JSON Merge-Patch semantics (RFC 7396).
 *
 * <p>Every field is a {@link Patched} for the reason the campaign editor's patch
 * gives: the rewards tab autosaves one input at a time, so a body containing only
 * the weight must leave the name alone, and one containing {@code "sku": null} must
 * clear it. {@link java.util.Optional} cannot express that difference — Jackson maps
 * both a missing property and an explicit null to empty.
 *
 * <p>{@code digital} and {@code weightGrams} arrive separately here and are applied
 * together, because the database refuses a digital item with a weight. The service
 * reads whichever half the client did not send from the stored row.
 */
public record ItemPatch(
        Patched<String> name,
        Patched<String> description,
        Patched<String> imageUrl,
        Patched<Integer> weightGrams,
        Patched<Boolean> digital,
        Patched<String> sku) {

    public ItemPatch {
        // Absence, not null, is the neutral value. Normalised here so that no
        // caller can produce a patch that reads as "clear everything".
        name = Patched.orAbsent(name);
        description = Patched.orAbsent(description);
        imageUrl = Patched.orAbsent(imageUrl);
        weightGrams = Patched.orAbsent(weightGrams);
        digital = Patched.orAbsent(digital);
        sku = Patched.orAbsent(sku);
    }

    /** Whether the physical nature of the item moves, which is a change to two columns at once. */
    public boolean changesPhysicality() {
        return digital.isPresent() || weightGrams.isPresent();
    }
}
