package az.ideanest.notification.application;

/**
 * The relay would not take a {@link TransactionalMail}.
 *
 * <p>Unchecked and published in the application layer for one reason: the callers are in
 * other modules, {@code MessagingException} and {@code MailException} are both
 * {@code notification.infrastructure}'s business, and a port whose failure could only be
 * caught by naming a JavaMail type would be a port that leaks the transport it exists to
 * hide.
 *
 * <p><strong>Thrown means nothing was sent.</strong> It does not mean the message will
 * not arrive later, because there is no later — this port has no queue.
 */
public class TransactionalMailFailedException extends RuntimeException {

    public TransactionalMailFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
