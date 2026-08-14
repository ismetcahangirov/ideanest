package az.ideanest.auth.application;

/**
 * A verification link that cannot be redeemed: unknown, expired, already spent,
 * or issued for something else.
 *
 * <p>The message distinguishes expired and already-used from unknown, which is
 * a deliberate trade. It tells someone holding a link why it failed — the
 * difference between "ask for another" and "you are already done" — and the
 * information it gives an attacker is worthless, because knowing that a random
 * 256-bit string is not a token is not a clue about which string would be.
 */
public class VerificationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VerificationRejectedException(String message) {
        super(message);
    }
}
