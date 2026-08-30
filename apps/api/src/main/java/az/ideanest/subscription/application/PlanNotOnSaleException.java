package az.ideanest.subscription.application;

import java.util.UUID;

/**
 * A plan that exists and is not being sold.
 *
 * <p>Its own refusal rather than a 404, because the two lead somewhere different: a
 * missing plan means the link was wrong, and an unlisted one means the catalogue moved on
 * while the page was open. The second is what a creator meets when they leave the pricing
 * tab open over an operator's repricing, and reloading fixes it.
 */
public class PlanNotOnSaleException extends RuntimeException {

    private final UUID planId;

    public PlanNotOnSaleException(UUID planId) {
        super("Plan " + planId + " is not on sale");
        this.planId = planId;
    }

    public UUID planId() {
        return planId;
    }
}
