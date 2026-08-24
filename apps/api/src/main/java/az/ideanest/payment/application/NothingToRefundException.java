package az.ideanest.payment.application;

import java.util.UUID;

/**
 * The pledge has no settled charge to reverse — #67.
 *
 * <p>409 rather than 404: the pledge exists and the refund does not apply to it. A 404
 * would send a member of staff looking for a typo in an identifier they pasted from the
 * screen in front of them.
 *
 * <p>Reached for a pledge that was never collected, and for one whose only charges were
 * declined. Both are the same answer, because there is the same amount of money to send
 * back.
 */
public class NothingToRefundException extends RuntimeException {

    public NothingToRefundException(UUID pledgeId) {
        super("Pledge " + pledgeId + " has no settled charge to refund");
    }
}
