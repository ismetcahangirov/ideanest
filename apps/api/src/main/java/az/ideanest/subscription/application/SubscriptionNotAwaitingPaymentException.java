package az.ideanest.subscription.application;

import az.ideanest.subscription.domain.SubscriptionState;
import java.util.UUID;

/**
 * Somebody tried to record a payment against a subscription that is not waiting for one.
 *
 * <p>The ordinary cause is two members of staff working the same queue: one records the
 * transfer, the other has the row still open on their screen and records it again. A
 * second activation would move the period start forward and hand the creator a free
 * extension, so it is refused with the state the row is actually in -- which tells the
 * loser that their colleague got there first rather than that something broke.
 */
public class SubscriptionNotAwaitingPaymentException extends RuntimeException {

    private final UUID subscriptionId;
    private final SubscriptionState state;

    public SubscriptionNotAwaitingPaymentException(UUID subscriptionId, SubscriptionState state) {
        super("Subscription " + subscriptionId + " is " + state + " and is not waiting for payment");
        this.subscriptionId = subscriptionId;
        this.state = state;
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }

    public SubscriptionState state() {
        return state;
    }
}
