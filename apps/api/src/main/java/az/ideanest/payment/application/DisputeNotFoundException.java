package az.ideanest.payment.application;

import java.util.UUID;

/**
 * No dispute at that identifier — #308.
 *
 * <p>404. Unlike a campaign, there is nothing to be evasive about: a caller who can reach
 * this endpoint at all is already staff with {@code VIEW_FINANCE}, so confirming that a
 * dispute exists discloses nothing they could not learn from the queue.
 */
public class DisputeNotFoundException extends RuntimeException {

    public DisputeNotFoundException(UUID disputeId) {
        super("No dispute " + disputeId);
    }
}
