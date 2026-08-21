package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.application.BackerSegment;
import az.ideanest.shared.audience.SegmentAudience;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who a saved segment currently matches, published so that other modules need not read
 * {@code backer_segments} or {@code pledges}.
 *
 * <p>The pledge module's half of #98. {@code SegmentAudience} is the question and this is the
 * only class that answers it, for the reason {@code PledgeProjectAudiences} gives about its own
 * table: both tables are this module's and {@code ModuleBoundaryTests} forbids anybody else from
 * naming them.
 *
 * <p><strong>Two reads, not one, and the first one is the access check that is not an access
 * check.</strong> The segment is looked up by campaign <em>and</em> identifier, so a segment
 * belonging to another campaign resolves to nothing rather than to somebody else's filter — the
 * same pairing {@code BackerSegmentService} uses, and the reason a caller cannot reach across
 * campaigns by guessing an identifier. There is no capability check here because there is
 * nobody to check: this is asked while translating an event, long after the person who sent the
 * message has gone, and the authorisation happened when they sent it.
 *
 * <p><strong>A segment that no longer exists is an empty audience.</strong> The interface says
 * so and the reason is {@code NotificationFanOut}'s: a segment deleted between the send and the
 * delivery must not be able to fail a dispatch that other modules share. What it costs is that
 * a message to a deleted segment reaches nobody and says nothing about why — which is why
 * {@code campaign_messages} freezes {@code recipient_count} at send time rather than leaving
 * "who did this reach" to be answered later by this method.
 */
@Component
public class PledgeSegmentAudience implements SegmentAudience {

    private final BackerSegmentRepository segments;
    private final BackerListRepository backers;

    public PledgeSegmentAudience(BackerSegmentRepository segments, BackerListRepository backers) {
        this.segments = segments;
        this.backers = backers;
    }

    @Override
    public List<UUID> membersOf(UUID projectId, UUID segmentId, int limit) {
        if (projectId == null || segmentId == null) {
            // An empty audience rather than a failure, for the reason the interface gives.
            return List.of();
        }
        if (limit < 1) {
            throw new IllegalArgumentException("An audience of at most " + limit + " people is not a question");
        }

        return segments.find(projectId, segmentId)
                .map(BackerSegment::filter)
                .map(filter -> backers.backerIds(projectId, filter, limit))
                .orElseGet(List::of);
    }
}
