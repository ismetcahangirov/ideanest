package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.BackerFulfilment;
import az.ideanest.shared.project.ProjectSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * A backer's own parcels, across every campaign they have backed — §4.8's PM-21.
 *
 * <p>Each row carries the campaign's name and path as well as the parcel, because a
 * list of pledge identifiers is a list nobody can read. The campaign fields are null
 * when it has been hard deleted since — {@code ProjectSummaries} publishes that
 * contract and this list must not fail because one campaign is gone.
 *
 * <p>A pledge the creator has said nothing about is <strong>absent</strong> rather than
 * reported as preparing. The platform does not invent a status on a creator's behalf.
 */
public record BackerFulfilmentResponse(List<Item> fulfilments) {

    public static BackerFulfilmentResponse of(List<BackerFulfilment> fulfilments) {
        return new BackerFulfilmentResponse(fulfilments.stream().map(Item::of).toList());
    }

    /** One parcel and the campaign it belongs to. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(
            UUID projectId,
            String projectTitle,
            String projectSlug,
            String creatorSlug,
            FulfilmentResponse fulfilment) {

        static Item of(BackerFulfilment backerFulfilment) {
            ProjectSummary campaign = backerFulfilment.campaign();
            return new Item(
                    backerFulfilment.fulfilment().getProjectId(),
                    campaign == null ? null : campaign.title(),
                    campaign == null ? null : campaign.slug(),
                    campaign == null ? null : campaign.creatorSlug(),
                    FulfilmentResponse.of(backerFulfilment.fulfilment()));
        }
    }
}
