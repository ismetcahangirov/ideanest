package az.ideanest.reward.application;

import java.util.UUID;

/**
 * No such reward tier, or none this caller is allowed to know about.
 *
 * <p><strong>One exception for both cases</strong>, for exactly the reason
 * {@code ProjectNotFoundException} gives: a tier belongs to a campaign, an
 * unlaunched campaign is confidential, and a reward tier is the most commercially
 * sensitive part of one — it is the price. Answering 403 for a tier that exists
 * under somebody else's campaign and 404 for an identifier that never existed
 * would make this endpoint an oracle for both.
 *
 * <p>That is why {@code RewardService} catches the project module's refusal and
 * rethrows this. The two answers have to be identical, and they are only identical
 * if they are the same exception.
 */
public class RewardNotFoundException extends RuntimeException {

    public RewardNotFoundException(UUID rewardId) {
        // The identifier and nothing else: the title and the price of an unlaunched
        // tier are the confidential parts, and this message reaches a log.
        super("No reward tier " + rewardId + " is visible to this caller");
    }
}
