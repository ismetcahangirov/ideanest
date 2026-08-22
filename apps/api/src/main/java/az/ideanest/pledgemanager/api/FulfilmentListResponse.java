package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.FulfilmentProgress;
import az.ideanest.pledgemanager.domain.Fulfilment;
import java.util.List;

/**
 * A campaign's parcels, with the counts beside them — §4.8's PM-22.
 *
 * <p>Both in one response because a creator opening this screen asks both questions at
 * once — "how far have I got" and "which ones have I not done" — and two requests would
 * let the answers disagree by one import.
 *
 * @param progress counts over every backing, including the ones with no row here yet.
 *     A list on its own could not report those: they are the pledges this table says
 *     nothing about, and they are what a creator is looking for
 */
public record FulfilmentListResponse(List<FulfilmentResponse> fulfilments, FulfilmentProgressResponse progress) {

    public static FulfilmentListResponse of(List<Fulfilment> fulfilments, FulfilmentProgress progress) {
        return new FulfilmentListResponse(
                fulfilments.stream().map(FulfilmentResponse::of).toList(),
                FulfilmentProgressResponse.of(progress));
    }
}
