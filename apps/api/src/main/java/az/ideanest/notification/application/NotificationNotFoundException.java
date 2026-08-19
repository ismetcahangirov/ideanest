package az.ideanest.notification.application;

/**
 * There is no such notification, or it is not this account's.
 *
 * <p><strong>One exception for both, and the handler gives them one answer.</strong>
 * {@code NotificationRepository.findByIdAndRecipientId} puts the recipient in the query
 * rather than checking it after the read for exactly this reason: an endpoint that could
 * tell "does not exist" from "somebody else's" is one that confirms the existence of
 * other people's notifications to anybody holding a token, and a notification's existence
 * is a fact about a person (§17.4).
 */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(String message) {
        super(message);
    }
}
