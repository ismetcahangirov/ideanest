package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.DiscoveryCursor;
import az.ideanest.discovery.domain.ProjectCard;
import java.util.List;

/**
 * One page of cards, and where the next one starts.
 *
 * <p><strong>No total count.</strong> §10.3 specifies cursor pagination and D-04
 * specifies infinite scroll, and neither needs one — but the reason it is absent is
 * stronger than that. A total means a second aggregate over the same predicate on
 * every page of every scroll, which is the single most expensive thing this endpoint
 * could do and would be paid a hundred times per scroll to render a number nobody
 * reads. {@code GET /v1/discover/facets} is where counts live, is asked for once per
 * filter change rather than once per page, and can be cached on its own terms.
 *
 * @param items in the order the sort asked for; at most {@code query.limit()}
 * @param nextCursor null when this is the last page. <strong>Null is the only signal
 *     that the feed has ended</strong> — a short page is not one, because a page can
 *     be short and still have more behind it, and a client that stopped on a short
 *     page would silently truncate the feed
 */
public record DiscoveryPage(List<ProjectCard> items, DiscoveryCursor nextCursor) {

    public DiscoveryPage {
        items = List.copyOf(items);
    }

    public static DiscoveryPage empty() {
        return new DiscoveryPage(List.of(), null);
    }
}
