package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.FulfilmentProgress;

/**
 * How far a campaign's fulfilment has got.
 *
 * <p>Counts and nothing else, like {@link AddressProgressResponse}: no backer, no
 * tracking number, nothing a creator has to be authorised twice for. That is what makes
 * it the number a dashboard may poll.
 */
public record FulfilmentProgressResponse(
        long backings, long preparing, long shipped, long delivered, long returned, long untouched) {

    public static FulfilmentProgressResponse of(FulfilmentProgress progress) {
        return new FulfilmentProgressResponse(
                progress.backings(),
                progress.preparing(),
                progress.shipped(),
                progress.delivered(),
                progress.returned(),
                progress.untouched());
    }
}
