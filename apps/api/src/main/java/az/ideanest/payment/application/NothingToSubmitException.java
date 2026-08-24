package az.ideanest.payment.application;

import java.util.UUID;

/**
 * Every piece of evidence on this case has already been sent — #308.
 *
 * <p>409, not 400. The request is well formed and the case is simply in a state where it
 * does nothing — which is the ordinary result of two people working one dispute, or of
 * somebody pressing the button twice on a slow connection.
 *
 * <p>Refused rather than treated as a success, deliberately. A silent no-op would move the
 * case to {@code UNDER_REVIEW} a second time and reset who is recorded as having answered
 * it, and the person who pressed the button would believe they had sent something.
 */
public class NothingToSubmitException extends RuntimeException {

    public NothingToSubmitException(UUID disputeId) {
        super("Dispute " + disputeId + " has no unsent evidence");
    }
}
