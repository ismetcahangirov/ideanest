package az.ideanest.reward.application;

import java.util.UUID;

/**
 * A reward tier somebody has claimed cannot be deleted. §5.3, without exception.
 *
 * <p>Answered as 409 with {@code code: REWARD_HAS_BACKERS}. A conflict rather than a
 * forbidden: the request is well formed and the caller is entitled to delete their
 * own tiers — what refuses it is that somebody has already chosen this one, and a
 * backer whose reward disappeared has been told nothing about what they are now
 * owed. §5.3 gives the alternative: the tier may be withdrawn from sale, which
 * closes it to new backers and leaves the existing ones with a promise that still
 * describes itself.
 *
 * <p><strong>{@code claimed_quantity} is the whole of the check today, and that is
 * a gap.</strong> There are no pledges yet — epic #50 introduces them and #52 the
 * backer report — so nothing writes that column and it is always zero in
 * production. The rule is therefore correct in code and untested against real
 * traffic until then. It is written now because the constraint it protects is being
 * created now, and because a delete endpoint shipped without it would have to be
 * retrofitted after the first campaign had backers.
 */
public class RewardHasBackersException extends RuntimeException {

    private final int claimedQuantity;

    public RewardHasBackersException(UUID rewardId, int claimedQuantity) {
        super("Reward tier " + rewardId + " has been claimed " + claimedQuantity + " times and cannot be deleted");
        this.claimedQuantity = claimedQuantity;
    }

    /** How many people chose it, so the client can say so rather than only refusing. */
    public int claimedQuantity() {
        return claimedQuantity;
    }
}
