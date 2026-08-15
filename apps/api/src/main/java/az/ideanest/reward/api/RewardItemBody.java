package az.ideanest.reward.api;

import az.ideanest.reward.application.RewardContent;
import az.ideanest.reward.application.RewardFieldRejectedException;
import az.ideanest.reward.domain.RewardTierItem;
import java.util.List;
import java.util.UUID;

/**
 * One line of a tier's composition, in a request and in a response.
 *
 * <p>One record for both directions because it is the same two values, and two would
 * drift the first time a field was added to one of them — the reasoning
 * {@code CoverImageBody} gives.
 *
 * <p>The item is referenced by identifier and not embedded. A tier's composition is
 * read alongside the campaign's item list, which the editor already holds, and
 * embedding the item would repeat the same mug in every tier that contains it —
 * inviting a client to edit one copy of it.
 */
public record RewardItemBody(UUID itemId, int quantity) {

    public static RewardItemBody of(RewardTierItem content) {
        return new RewardItemBody(content.getItemId(), content.getQuantity());
    }

    public RewardContent toContent() {
        return new RewardContent(itemId, quantity);
    }

    /**
     * A whole composition, as the application layer takes it.
     *
     * <p>A null line — {@code "items": [null]} — is refused here rather than carried
     * further. Everything downstream copies the list defensively, and a null element in
     * an immutable copy is a {@link NullPointerException} and a 500 for what is plainly
     * a malformed body.
     *
     * @param bodies null means an empty composition, which is what a tier containing
     *     nothing is
     */
    public static List<RewardContent> contentsOf(List<RewardItemBody> bodies) {
        if (bodies == null) {
            return List.of();
        }
        if (bodies.contains(null)) {
            throw new RewardFieldRejectedException("items", "Each line of a reward names an item and a quantity.");
        }
        return bodies.stream().map(RewardItemBody::toContent).toList();
    }
}
