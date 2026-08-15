package az.ideanest.reward.application;

/**
 * §5.3: a campaign has between zero and a hundred reward tiers.
 *
 * <p>Answered as 409 with {@code code: TOO_MANY_REWARDS} and the limit in
 * {@code meta}. A conflict rather than a bad request: the tier being created is
 * perfectly valid, and what refuses it is how many already exist.
 *
 * <p>Not a check constraint, unlike almost everything else here. A count across
 * rows cannot be expressed as one, and the alternatives — a trigger, or a
 * denormalised counter on {@code projects} — both cost more than the rule is worth:
 * an upper bound on how long a reward list may be is a limit on a creator's
 * patience with their own page, not an invariant money depends on. It is checked in
 * one place, on the one path that creates a tier.
 */
public class TooManyRewardsException extends RuntimeException {

    private final int limit;

    public TooManyRewardsException(int limit) {
        super("A campaign has at most " + limit + " reward tiers");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
