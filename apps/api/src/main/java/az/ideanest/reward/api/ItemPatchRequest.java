package az.ideanest.reward.api;

import az.ideanest.reward.application.ItemPatch;
import az.ideanest.shared.Patched;

/**
 * A partial edit of an item, with JSON Merge-Patch semantics (RFC 7396).
 *
 * <p>Every field is a {@link Patched}: a field the client did not mention is left
 * alone, and one explicitly set to {@code null} is cleared. The rewards tab autosaves
 * one input at a time, so a body containing only {@code weightGrams} is the normal
 * case — and reading that as "set the weight, clear everything else" would delete a
 * creator's work in a request that looks entirely ordinary in a log.
 *
 * <p>Bean validation annotations are deliberately absent, for the reason
 * {@code ProjectPatchRequest} gives: they cannot see inside a {@code Patched}, and a
 * rule enforced here as well as in the service would be two rules that can disagree.
 * The lengths are checked in {@code ItemService} and in the database beneath it.
 */
public record ItemPatchRequest(
        Patched<String> name,
        Patched<String> description,
        Patched<String> imageUrl,
        Patched<Integer> weightGrams,
        Patched<Boolean> isDigital,
        Patched<String> sku) {

    public ItemPatchRequest {
        // Absence is the neutral value, so a null component becomes absent rather than
        // "clear this field". Jackson's absent hook already does this; this is the belt
        // to its braces.
        name = Patched.orAbsent(name);
        description = Patched.orAbsent(description);
        imageUrl = Patched.orAbsent(imageUrl);
        weightGrams = Patched.orAbsent(weightGrams);
        isDigital = Patched.orAbsent(isDigital);
        sku = Patched.orAbsent(sku);
    }

    public ItemPatch toPatch() {
        return new ItemPatch(name, description, imageUrl, weightGrams, isDigital, sku);
    }
}
