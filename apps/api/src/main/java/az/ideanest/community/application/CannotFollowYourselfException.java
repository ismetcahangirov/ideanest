package az.ideanest.community.application;

/**
 * The account being followed is the account doing the following.
 *
 * <p>A 422. {@code follows_is_not_self} would refuse the row regardless, and this exists so
 * that the refusal arrives as a sentence rather than as a constraint-violation stack trace with
 * a 500 in front of it.
 *
 * <p><strong>Why it is refused at all</strong> is on {@code V32} and on
 * {@code ProjectAudience.FOLLOWERS}: a self-follow would put the creator in their own
 * {@code FOLLOWERS} audience, so launching a campaign would notify them that somebody they
 * follow had launched one — alongside the message they already get for being the creator.
 */
public class CannotFollowYourselfException extends RuntimeException {

    public CannotFollowYourselfException() {
        super("An account cannot follow itself");
    }
}
