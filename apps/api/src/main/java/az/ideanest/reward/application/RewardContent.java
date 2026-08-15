package az.ideanest.reward.application;

import java.util.UUID;

/**
 * One line of a tier's composition: an item, and how many of it.
 *
 * @param quantity at least one. Two of the same mug in a tier is an ordinary
 *     reward; zero of something is a line that should not have been sent
 */
public record RewardContent(UUID itemId, int quantity) {
}
