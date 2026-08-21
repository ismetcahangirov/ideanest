package az.ideanest.community.application;

import az.ideanest.community.domain.CampaignMessage;
import java.util.List;

/**
 * One page of what a campaign has sent, and where the next one starts.
 *
 * @param items the messages, newest first
 * @param next where to continue, or null when this is the last page
 */
public record CampaignMessagePage(List<CampaignMessage> items, SignalCursor next) {

    public CampaignMessagePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
