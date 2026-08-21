package az.ideanest.community.application;

import java.util.List;

/**
 * One page of the accounts somebody follows, and where the next one starts.
 *
 * <p>The mirror of {@link SavedCampaignPage}, and its note about a short page applies here for
 * the same reason: an account closed under §17.4 no longer resolves, so the row is dropped from
 * the page rather than rendered as somebody with no name.
 *
 * @param items the accounts, newest follow first
 * @param next where to continue, or null when this is the last page
 */
public record FollowedCreatorPage(List<FollowedCreator> items, SignalCursor next) {

    public FollowedCreatorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
