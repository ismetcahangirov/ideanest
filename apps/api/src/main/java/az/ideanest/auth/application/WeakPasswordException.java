package az.ideanest.auth.application;

/**
 * A password that does not meet the policy.
 *
 * <p>The message is shown to the person who chose it, so it says what is wrong
 * and never echoes the password back.
 */
public class WeakPasswordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WeakPasswordException(String message) {
        super(message);
    }
}
