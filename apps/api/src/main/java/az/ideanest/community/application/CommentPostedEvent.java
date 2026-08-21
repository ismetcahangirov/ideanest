package az.ideanest.community.application;

import az.ideanest.community.domain.Comment;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code comment.posted}: somebody said something on a campaign's page — §12.1's
 * {@code project:{id}:comments}.
 *
 * <p>Recorded through §8.3's outbox in the transaction that writes the comment, so a comment that
 * rolled back is one nothing was told about.
 *
 * <h2>What is deliberately not in it</h2>
 *
 * <p><strong>The body.</strong> The only consumer is the realtime module, which broadcasts a
 * count and an identifier and never text — {@code RealtimeAggregator} argues why at length: a
 * comment can be removed by its author, by the campaign's team or by moderation seconds after it
 * is posted, and a socket has no way to take a message back. Carrying the text in the event would
 * put it one careless consumer away from being published past every control the platform has for
 * removing it.
 *
 * <p><strong>The author.</strong> Same reason in a different direction: nothing that consumes
 * this needs to know who spoke, and a live counter holding an account identifier is personal data
 * in a module with no table and no retention rule.
 *
 * <p>What is left is exactly what §12.1 needs to route and to count. {@code Outbox} asks for
 * "enough to route on, and no more", and for once that is the whole payload rather than a
 * compromise with it.
 *
 * <p><strong>Replies are in, and roots and replies are not distinguished.</strong> A page showing
 * "3 new comments" counts both, because both are new things to read; a consumer that needed the
 * difference would be a consumer rendering the thread, which this event cannot support and is not
 * for.
 *
 * @param commentId which comment. The newest one in a window is broadcast so a client can decide
 *     whether it has already loaded it
 * @param projectId the campaign, which is the aggregate and the channel
 * @param postedAt when it was written
 */
public record CommentPostedEvent(UUID commentId, UUID projectId, Instant postedAt) {

    /**
     * §7.2's aggregate name.
     *
     * <p>{@code project} rather than {@code comment}, and the difference is ordering: §8.3
     * dispatches events for one aggregate in the order they were recorded and gives no order
     * between aggregates. Comments on one campaign should reach a page in the order they were
     * written, and keying them by their own identifiers would make every comment its own
     * aggregate with no order between any of them.
     */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "comment.posted";

    static CommentPostedEvent of(Comment comment) {
        return new CommentPostedEvent(comment.getId(), comment.getProjectId(), comment.getCreatedAt());
    }
}
