package az.ideanest.payout.application;

import az.ideanest.payout.domain.PayoutState;
import java.util.UUID;

/**
 * This payout cannot be sent - #306.
 *
 * <p>409. Two causes, and the second is the interesting one:
 *
 * <ul>
 *   <li>It has not been approved, or has already been sent.
 *   <li><strong>The figures moved underneath it.</strong> A refund issued between the
 *       calculation and the instruction changes what the campaign holds, and the payout's
 *       figures are frozen - so sending would pay out money that has gone back to a
 *       backer. PayoutService cancels it and refuses, because a different amount is a
 *       different decision and the signatures on file were given for this one.
 * </ul>
 */
public class PayoutNotSendableException extends RuntimeException {

    private final transient PayoutState state;

    public PayoutNotSendableException(UUID payoutId, PayoutState state) {
        super("Payout " + payoutId + " is " + state + " and cannot be sent");
        this.state = state;
    }

    public PayoutState state() {
        return state;
    }
}
