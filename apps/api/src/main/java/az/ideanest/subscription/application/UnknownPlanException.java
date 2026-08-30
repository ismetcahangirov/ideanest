package az.ideanest.subscription.application;

import java.util.UUID;

/**
 * A plan identifier that names nothing.
 *
 * <p>A 404 from the console and from the pricing page alike. The pricing page reaches it
 * when a plan was unlisted and then, later, the row was found to be referenced by nothing
 * and removed by hand -- which is the only way a plan disappears, because the service
 * never deletes one.
 */
public class UnknownPlanException extends RuntimeException {

    private final UUID planId;

    public UnknownPlanException(UUID planId) {
        super("No subscription plan " + planId);
        this.planId = planId;
    }

    public UUID planId() {
        return planId;
    }
}
