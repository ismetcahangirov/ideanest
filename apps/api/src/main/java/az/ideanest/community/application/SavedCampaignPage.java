package az.ideanest.community.application;

import java.util.List;

/**
 * One page of somebody's saved campaigns, and where the next one starts.
 *
 * @param items the campaigns, newest save first. Possibly shorter than the page size, and
 *     possibly empty — a saved campaign that has since been hard deleted is dropped rather
 *     than shown as a link to nothing, so a short page is not the same as the end of the list
 * @param next where to continue, or null when this is the last page. <strong>Derived from the
 *     rows read rather than from the rows returned</strong>, which is why the two can differ:
 *     the cursor names the last row of the underlying page, including one whose campaign was
 *     dropped, so paging cannot stall on a deleted campaign at a page boundary
 */
public record SavedCampaignPage(List<SavedCampaign> items, SignalCursor next) {

    public SavedCampaignPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
