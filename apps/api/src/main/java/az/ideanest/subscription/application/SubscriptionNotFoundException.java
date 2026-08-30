package az.ideanest.subscription.application;

import java.util.UUID;

/** A subscription identifier the console named that resolves to nothing. */
public class SubscriptionNotFoundException extends RuntimeException {

    private final UUID subscriptionId;

    public SubscriptionNotFoundException(UUID subscriptionId) {
        super("No subscription " + subscriptionId);
        this.subscriptionId = subscriptionId;
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }
}
