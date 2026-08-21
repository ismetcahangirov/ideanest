package az.ideanest.community.api;

import az.ideanest.community.application.CampaignMessagePage;
import java.util.List;

/**
 * One page of what a campaign has sent.
 *
 * @param items the messages, newest first
 * @param nextCursor the opaque value to pass back as {@code ?cursor=}, or null at the end of
 *     the list. Null rather than absent or empty, for {@link SavedListResponse}'s reason
 */
public record CampaignMessageListResponse(List<CampaignMessageResponse> items, String nextCursor) {

    public static CampaignMessageListResponse of(CampaignMessagePage page) {
        return new CampaignMessageListResponse(
                page.items().stream().map(CampaignMessageResponse::of).toList(),
                page.next() == null ? null : page.next().encode());
    }
}
