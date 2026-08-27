package az.ideanest.verification.application;

/**
 * A stored document that cannot be opened — issue #105.
 *
 * <p>Two causes and both are operational rather than anybody's mistake: the key named on
 * the row is not configured on this deployment, or the ciphertext failed its
 * authentication tag — a row somebody edited, or a restore that went wrong.
 *
 * <p>Neither is something a creator can fix by submitting again, so this is never reported
 * to them as a validation problem. {@code AddressUnreadableException} is the same
 * exception for the same reason one module over.
 */
public class DocumentUnreadableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentUnreadableException(String message) {
        super(message);
    }

    public DocumentUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
