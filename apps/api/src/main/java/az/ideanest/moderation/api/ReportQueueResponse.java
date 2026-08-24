package az.ideanest.moderation.api;

import az.ideanest.moderation.application.ReportQueuePage;
import java.util.List;
import java.util.UUID;

/**
 * One page of the queue.
 *
 * @param state which state was asked for, echoed so a client rendering two tabs can
 *     tell which response landed in which
 * @param target which kind of reported thing was asked for, echoed for the same reason
 *     and absent when the request asked for every kind. AD-09 draws the campaign queue
 *     and the profile queue from this one endpoint, and a screen that rendered the wrong
 *     one of those would be showing complaints about people under a heading about
 *     campaigns
 * @param reports oldest first, because a queue is worked in the order things arrived
 * @param nextCursor what to send as {@code after} for the next page, or null when
 *     this was the last one. There is no total; {@link ReportQueuePage} has why
 */
public record ReportQueueResponse(
        String state, String target, List<QueuedReportResponse> reports, UUID nextCursor) {

    static ReportQueueResponse of(ReportQueuePage page) {
        return new ReportQueueResponse(
                page.state().name(),
                page.targetType() == null ? null : page.targetType().name(),
                page.reports().stream().map(QueuedReportResponse::of).toList(),
                page.nextCursor());
    }
}
