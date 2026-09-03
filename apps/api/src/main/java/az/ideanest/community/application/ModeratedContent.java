package az.ideanest.community.application;

import az.ideanest.community.domain.Comment;
import az.ideanest.community.domain.ProjectUpdate;
import az.ideanest.community.infrastructure.CommentRepository;
import az.ideanest.community.infrastructure.ProjectUpdateRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A comment or an update as a moderator has to be able to read it — #399.
 *
 * <h2>Why this is not a method on {@code PublicComments}</h2>
 *
 * <p>That class answers "may a stranger see this", and the answer it gives is exactly the
 * one that makes it useless here: a removed comment is invisible to the public, and a
 * report about a removed comment is the report a moderator most needs to read. So does a
 * comment under a suspended campaign — the campaign was suspended <em>because</em> of
 * complaints like this one, and the rule that hides it from the public would hide the
 * evidence from the person deciding.
 *
 * <p>Widening those methods was the alternative and would have been the wrong repair:
 * {@code requireReportable} is called on the intake path by anybody with an account, and a
 * flag that relaxes it is one boolean away from an endpoint that serves a draft campaign's
 * comments to a stranger. Two methods that cannot be confused, in two classes named after
 * their audiences, is what the module already does for {@code CommentService} and
 * {@code PublicComments} and for the same reason.
 *
 * <h2>There is no authorisation here, deliberately</h2>
 *
 * <p>The same arrangement {@code AuditTrail} states: this is a read the moderation module
 * asks for, and the refusal lives one layer out in {@code ReportedContent}, which requires
 * platform staff before it calls anything here. A capability check in this class would be
 * the community module deciding who moderates, which is not its question — and it would be
 * a second copy of a rule {@code ReportModerationService} already applies to the report
 * this content belongs to.
 *
 * <h2>Nothing here is reachable without a report</h2>
 *
 * <p>Both methods take an identifier and neither lists. A member of staff cannot page
 * through everybody's comments from this class; they can read the one somebody complained
 * about, which is the access the queue's decision actually needs.
 */
@Service
public class ModeratedContent {

    private final CommentRepository comments;
    private final ProjectUpdateRepository updates;

    public ModeratedContent(CommentRepository comments, ProjectUpdateRepository updates) {
        this.comments = comments;
        this.updates = updates;
    }

    /**
     * The reported comment, removed or not.
     *
     * @return empty when the identifier names nothing at all. A comment that was taken
     *     down is <strong>not</strong> empty: V25 keeps the row and its body precisely so
     *     that a report filed before the removal can still be decided, and answering
     *     "there is nothing there" would throw away the reason that column was kept
     */
    @Transactional(readOnly = true)
    public Optional<ModeratedComment> comment(UUID commentId) {
        return comments.findById(commentId).map(ModeratedContent::describe);
    }

    /**
     * The reported update, published or scheduled.
     *
     * <p>A scheduled update is served here where {@code PublicProjectUpdates} refuses it.
     * Nothing outside the campaign's team can report one — that is the oracle that class
     * spends a paragraph closing — but an update can be reported and then edited back to a
     * future {@code publishedAt}, and a queue that stopped showing it at that moment would
     * be a queue an author can empty by rescheduling.
     */
    @Transactional(readOnly = true)
    public Optional<ModeratedUpdate> update(UUID updateId) {
        return updates.findById(updateId).map(ModeratedContent::describe);
    }

    private static ModeratedComment describe(Comment comment) {
        return new ModeratedComment(
                comment.getId(),
                comment.getProjectId(),
                comment.getAuthorId(),
                comment.getBody(),
                comment.isDeleted(),
                comment.getCreatedAt());
    }

    private static ModeratedUpdate describe(ProjectUpdate update) {
        return new ModeratedUpdate(
                update.getId(),
                update.getProjectId(),
                update.getAuthorId(),
                update.getNumber(),
                update.getTitle(),
                update.getBody(),
                update.getPublishedAt());
    }

    /**
     * One comment, as the moderation queue needs to render it.
     *
     * @param body what was written, verbatim and never interpreted. It is untrusted text
     *     from one member of the public about another and the console renders it as text
     * @param removed whether it has since been taken down. Carried rather than used to
     *     decide whether to answer: the screen says "this comment has been removed" above
     *     the text, which is a different thing from an empty card
     */
    public record ModeratedComment(
            UUID id, UUID projectId, UUID authorId, String body, boolean removed, Instant createdAt) {
    }

    /**
     * One update, the same way.
     *
     * @param number §10.2's per-campaign sequence. The thing a creator and a backer both
     *     call it — "update 4" — and the only handle on it that is not a UUID
     * @param publishedAt when it went out, or when it is due to. Null on neither: the
     *     column is {@code NOT NULL}
     */
    public record ModeratedUpdate(
            UUID id, UUID projectId, UUID authorId, int number, String title, String body, Instant publishedAt) {
    }
}
