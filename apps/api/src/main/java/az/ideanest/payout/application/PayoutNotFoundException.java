package az.ideanest.payout.application;

import java.util.UUID;

/**
 * No payout at that identifier - #306.
 *
 * <p>404. Nothing to be evasive about: a caller who reaches this endpoint at all holds
 * VIEW_FINANCE, so confirming that a payout exists tells them nothing the queue would not.
 */
public class PayoutNotFoundException extends RuntimeException {

    public PayoutNotFoundException(UUID payoutId) {
        super("No payout " + payoutId);
    }
}
