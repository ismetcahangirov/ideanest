package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportState;
import java.util.List;
import java.util.UUID;

/**
 * One page of the queue.
 *
 * <p><strong>A cursor and no total.</strong> Counting every report in a state is a
 * second query over the whole table for a number that is stale before it renders,
 * and the decision a moderator makes from it — "is there more" — is answered exactly
 * by whether {@link #nextCursor()} is present. {@code DiscoveryCursor} makes the
 * same trade for the same reason on a feed nobody paginates to the end of either.
 *
 * @param state which state was asked for
 * @param reports oldest first, because a queue is worked in the order things arrived
 * @param nextCursor what to pass as {@code after} for the next page, or null when
 *     this was the last one. It is the identifier of the last report on this page,
 *     which is a UUID v7 and therefore a position in arrival order (§7.3)
 */
public record ReportQueuePage(ReportState state, List<QueuedReport> reports, UUID nextCursor) {

    public ReportQueuePage {
        reports = List.copyOf(reports);
    }
}
