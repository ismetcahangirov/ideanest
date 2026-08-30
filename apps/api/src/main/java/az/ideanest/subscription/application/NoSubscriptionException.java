package az.ideanest.subscription.application;

/**
 * The account has nothing to cancel.
 *
 * <p>A 404 rather than a quiet success. Cancelling something that is not there is a
 * request made under a mistaken belief -- usually a second tab that cancelled it already
 * -- and answering "done" would leave the creator believing they had just ended a
 * subscription they no longer had.
 */
public class NoSubscriptionException extends RuntimeException {

    public NoSubscriptionException() {
        super("This account holds no subscription");
    }
}
