package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import java.util.List;
import java.util.UUID;

/**
 * One page of the campaign directory.
 *
 * @param state the filter that produced it, or null for every state. Echoed back so a
 *     screen drawing a page it asked for asynchronously can tell which answer it is
 *     looking at
 * @param campaigns the rows, newest first
 * @param nextCursor the campaign to page after, or null when this page is the end of the
 *     list. A full page is the only honest signal that there may be more
 */
public record CampaignDirectoryPage(ProjectState state, List<DirectoryCampaign> campaigns, UUID nextCursor) {

    public CampaignDirectoryPage {
        campaigns = List.copyOf(campaigns);
    }
}
