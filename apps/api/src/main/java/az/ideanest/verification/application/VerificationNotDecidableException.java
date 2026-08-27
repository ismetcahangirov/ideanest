package az.ideanest.verification.application;

/**
 * A verification that cannot take the decision somebody is trying to record — issue #105.
 *
 * <p>The realistic cause is two members of staff opening the same queue: the second one
 * presses approve on a submission their colleague has already decided. That is a conflict
 * rather than a mistake, so it answers 409 and the queue is refreshed.
 */
public class VerificationNotDecidableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VerificationNotDecidableException(String message) {
        super(message);
    }
}
