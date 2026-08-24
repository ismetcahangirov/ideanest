package az.ideanest.payment.application;

import java.util.UUID;

/**
 * A provider raised a dispute against a charge the platform has no record of — #68.
 *
 * <p>Not reachable from any endpoint a person can call: {@code DisputeService.notified} is
 * called from the webhook path. What it produces there is a failed webhook delivery, which
 * V43's retry will bring back — and that is the right behaviour, because the usual cause
 * is a dispute notification arriving before the charge it is about has been written.
 *
 * <p>The alternative — opening a dispute with a null charge — would put a case on the
 * queue that nobody can answer, against money nobody can find.
 */
public class UnknownDisputeChargeException extends RuntimeException {

    public UnknownDisputeChargeException(UUID chargeTransactionId) {
        super("No charge " + chargeTransactionId + " to dispute");
    }
}
