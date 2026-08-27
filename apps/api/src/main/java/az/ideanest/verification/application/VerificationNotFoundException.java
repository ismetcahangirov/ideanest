package az.ideanest.verification.application;

/**
 * No such verification — issue #105.
 *
 * <p>Answered for a verification that does not exist <strong>and</strong> for one belonging
 * to somebody else, deliberately and for {@code NotificationNotFoundException}'s reason:
 * whether a given person is being identity-checked is a fact about them (§17.4), so an
 * endpoint that told the two apart would let anybody holding a token confirm it.
 */
public class VerificationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VerificationNotFoundException() {
        super("No such verification");
    }
}
