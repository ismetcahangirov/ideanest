package az.ideanest.community.application;

import az.ideanest.community.domain.ProjectUpdate;
import java.util.List;

/**
 * One page of a campaign's updates, and where the next one starts.
 *
 * @param updates newest first, which is the order the tab is read in
 * @param nextCursor what to send as {@code ?cursor=} for the following page, or null
 *     when this is the last one. <strong>Null rather than an empty string</strong>: a
 *     client should be able to test for "there is more" without knowing that the
 *     cursor happens to be a number
 * @param includesScheduled whether this page can contain updates that are not yet
 *     readable by the public — true only for the campaign's own team. It travels with
 *     the page because the response's cache policy depends on it, and deriving it a
 *     second time in the controller would be a second place to get it wrong
 */
public record UpdateTimeline(List<ProjectUpdate> updates, Integer nextCursor, boolean includesScheduled) {

    public UpdateTimeline {
        updates = List.copyOf(updates);
    }
}
