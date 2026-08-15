package az.ideanest.user.application;

/**
 * The password confirming a destructive action was not the account's.
 *
 * <p>Separate from authentication failure. The caller is authenticated; what
 * they failed is the second check that closing an account requires, and the
 * client should ask for the password again rather than sign the user in again.
 */
public class IncorrectPasswordException extends RuntimeException {

    public IncorrectPasswordException(String message) {
        super(message);
    }
}
