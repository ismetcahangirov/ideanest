package az.ideanest.shared.audience;

import java.util.List;
import java.util.UUID;

/**
 * "Who is in this saved segment", asked from outside the module that knows.
 *
 * <p>A second interface beside {@link ProjectAudiences} rather than a constant on
 * {@link ProjectAudience}, and the reason is the shape of the question rather than taste. Every
 * audience in that vocabulary is a <em>standing</em> group named by a word — a campaign's
 * backers, its savers, its creator's followers — and {@code membersOf} takes a campaign and a
 * name. A segment is a saved filter identified by a row, so asking for one needs an argument the
 * enum cannot carry. Adding a {@code SEGMENT} constant would mean either a method with a
 * parameter meaningful for one constant and ignored for the other three, or a second method on
 * the same interface, which is the same split with the seam hidden.
 *
 * <p>What the two share is the property that matters: the answer lives in the module that owns
 * the rows. {@code backer_segments} and {@code pledges} are both the pledge module's, so
 * {@code PledgeSegmentAudience} is the only implementation and the notification module depends
 * on the question.
 *
 * <p><strong>The bound is the caller's, and truncation is detectable.</strong> Both rules are
 * {@link ProjectAudiences}' and the arguments are identical: a segment can match every backer of
 * a successful campaign, so an unbounded answer is an unbounded fan-out inside one transaction;
 * and a caller that needs to know whether it saw everybody asks for one more than it can use and
 * compares, because an audience silently cut short is a set of people who were not told
 * something.
 */
public interface SegmentAudience {

    /**
     * The backers a saved segment currently matches.
     *
     * <p><strong>Currently.</strong> A segment stores the question and never the answer — V31
     * argues that at length — so this is evaluated against {@code pledges} on every call, and
     * two calls a week apart legitimately differ. That is the property that makes a segment
     * worth saving and it is also the one that makes {@code campaign_messages.recipient_count}
     * a frozen column rather than a join.
     *
     * @param projectId the campaign the segment belongs to. Passed as well as the segment
     *     identifier so that a segment cannot be read across campaigns by guessing its
     *     identifier — the same reason {@code BackerSegmentService} takes both
     * @param segmentId which saved filter
     * @param limit the most the caller can use, at least one. Refused rather than clamped when
     *     it is not positive, for {@link ProjectAudiences#membersOf}'s reason
     * @return the backers, distinct, in a stable order, at most {@code limit} of them.
     *     <strong>Empty for a segment that does not exist on this campaign</strong>, and for one
     *     that matches nobody, deliberately without distinguishing them: this is consumed while
     *     translating an event that other modules also consume, so it must not be able to fail
     *     their dispatch over a segment somebody deleted between the send and the delivery
     */
    List<UUID> membersOf(UUID projectId, UUID segmentId, int limit);
}
