package az.ideanest.pledgemanager.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The creator has frozen this address — §4.8's PM-08.
 *
 * <p>409 rather than 403: the backer is permitted to edit their own address, and what
 * has changed is the state of the thing rather than their authority over it. A client
 * that showed "you are not allowed" would be telling them something false about
 * themselves.
 *
 * <p>Carries the instant, because the only useful thing to say to somebody in this
 * position is when it happened and that they should contact the creator.
 */
public class AddressLockedException extends RuntimeException {

    private final UUID pledgeId;
    private final Instant lockedAt;

    public AddressLockedException(UUID pledgeId, Instant lockedAt) {
        super("The address on pledge " + pledgeId + " was locked at " + lockedAt);
        this.pledgeId = pledgeId;
        this.lockedAt = lockedAt;
    }

    public UUID pledgeId() {
        return pledgeId;
    }

    public Instant lockedAt() {
        return lockedAt;
    }
}
