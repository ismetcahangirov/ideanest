package az.ideanest.pledgemanager.application;

/**
 * A stored address that cannot be decrypted.
 *
 * <p>Two causes, and both are operational: the key the row names is not configured on
 * this deployment, or the ciphertext failed its authentication tag. Neither is
 * anything a backer or a creator can act on, so it answers 500 rather than a 4xx —
 * the request was valid and the platform could not honour it.
 *
 * <p><strong>Never swallowed into an empty address.</strong> An unreadable row must
 * not come back as a blank form: the backer would fill it in again, the write would
 * overwrite a row that was merely unreadable rather than absent, and an incident
 * would become data loss.
 */
public class AddressUnreadableException extends RuntimeException {

    public AddressUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AddressUnreadableException(String message) {
        super(message);
    }
}
