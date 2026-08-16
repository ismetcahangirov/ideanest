package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * The reward tier has no places left. §10.4's {@code REWARD_SOLD_OUT}.
 *
 * <p>A 409 rather than a 400 or a 404: the request is well formed, the tier
 * exists, and the caller was entitled to ask — what refuses it is that somebody
 * else took the last place, which may have happened while this backer was reading
 * the page. That distinction is the whole reason §10.4's example carries
 * {@code availableAlternatives}: the honest answer is not "no" but "not this one,
 * and here is what is left".
 *
 * <p><strong>The alternatives are not here.</strong> Assembling them means reading
 * the campaign's other tiers, which is a projection the reward module owns and the
 * response body of an endpoint that does not exist yet (#52). This carries the
 * tier that was refused, which is the one thing only this call knows; #52 maps it
 * to the problem detail and decides what to offer instead.
 *
 * <p>Thrown when the conditional update took no place — which covers a tier that
 * is full and a tier that has been deleted since it was priced a statement
 * earlier. Both are "there is no place for this backer" and the second is rare
 * enough that inventing a separate answer for it would be a code path nobody could
 * exercise.
 */
public class RewardSoldOutException extends RuntimeException {

    private final UUID rewardTierId;

    public RewardSoldOutException(UUID rewardTierId) {
        super("Reward tier " + rewardTierId + " has no remaining places");
        this.rewardTierId = rewardTierId;
    }

    /** The tier that was refused, so the client can name it and offer what is left. */
    public UUID rewardTierId() {
        return rewardTierId;
    }
}
