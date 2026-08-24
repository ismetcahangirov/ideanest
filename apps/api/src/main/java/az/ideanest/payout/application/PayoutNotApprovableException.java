package az.ideanest.payout.application;

import az.ideanest.payout.domain.PayoutState;
import java.util.UUID;

/**
 * This payout is not waiting for a signature - #306.
 *
 * <p>409, and the state travels with it, because the two reasons a reader meets this are
 * different problems: a payout still inside its hold is one to come back to, and a payout
 * already sent is one somebody else has dealt with.
 *
 * <p><strong>Approving during the hold is refused deliberately.</strong> The hold exists so
 * that refunds and chargebacks land before the money leaves; a signature given before them
 * is a signature on a figure nobody could yet know was right.
 */
public class PayoutNotApprovableException extends RuntimeException {

    private final transient PayoutState state;

    public PayoutNotApprovableException(UUID payoutId, PayoutState state) {
        super("Payout " + payoutId + " is " + state + " and is not waiting for approval");
        this.state = state;
    }

    /** Where it actually is, for the message the console shows. */
    public PayoutState state() {
        return state;
    }
}
