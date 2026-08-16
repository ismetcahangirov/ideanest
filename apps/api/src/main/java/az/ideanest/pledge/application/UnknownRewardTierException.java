package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * The campaign has no such reward tier.
 *
 * <p>Distinct from {@link RewardSoldOutException} because the two are different
 * mistakes with different answers. A sold-out tier is a race the backer lost and
 * the client should offer them what is left; a tier that is not part of this
 * campaign is a client that asked for the wrong thing — a stale page, a copied
 * identifier, or a tier the creator withdrew — and there is nothing to offer
 * instead of it.
 *
 * <p><strong>The campaign is part of the question, not a check afterwards.</strong>
 * A tier that exists under a different campaign is refused here for the same
 * reason V17's foreign key is composite: a pledge that named it would be a backer
 * buying one campaign's reward on another campaign's page.
 *
 * <p>The message carries identifiers and nothing else. A tier's title and price
 * are confidential before launch, and this text reaches a log.
 */
public class UnknownRewardTierException extends RuntimeException {

    private final UUID rewardTierId;

    public UnknownRewardTierException(UUID projectId, UUID rewardTierId) {
        super("Campaign " + projectId + " has no reward tier " + rewardTierId);
        this.rewardTierId = rewardTierId;
    }

    public UUID rewardTierId() {
        return rewardTierId;
    }
}
