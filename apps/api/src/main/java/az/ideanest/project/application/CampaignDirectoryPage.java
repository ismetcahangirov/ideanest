package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import java.util.List;
import java.util.UUID;

/**
 * One page of the campaign directory.
 *
 * @param state the state filter that produced it, or null for every state
 * @param creatorId the creator filter that produced it, or null for everybody's campaigns
 * @param query the search that produced it, trimmed, or null when there was none. Echoed
 *     already trimmed rather than as it was sent, because that is what was applied — a
 *     client that submitted a stray space and got no results should be able to see from the
 *     answer that the space was not the reason
 * @param campaigns the rows, newest first
 * @param nextCursor the campaign to page after, or null when this page is the end of the
 *     list. A full page is the only honest signal that there may be more
 *
 *     <p>All three filters are echoed for one reason: a screen that asks for a page
 *     asynchronously has to be able to tell which answer it is looking at. A search box that
 *     has been typed into twice has two requests in flight, and the second answer arriving
 *     first would otherwise leave the screen showing the wrong list under the right term.
 */
public record CampaignDirectoryPage(
        ProjectState state, UUID creatorId, String query, List<DirectoryCampaign> campaigns, UUID nextCursor) {

    public CampaignDirectoryPage {
        campaigns = List.copyOf(campaigns);
    }
}
