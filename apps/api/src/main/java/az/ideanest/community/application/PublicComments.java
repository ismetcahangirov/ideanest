package az.ideanest.community.application;

import az.ideanest.community.infrastructure.CommentRepository;
import az.ideanest.community.domain.Comment;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.PublicProjects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What another module is allowed to know about a comment.
 *
 * <p><strong>This exists for one caller</strong>: {@code ReportTargets}, which has to
 * establish that there is something at an identifier before the moderation queue
 * accepts a complaint about it. §10.2's {@code POST /v1/comments/{id}/report} was left
 * unpublished by #102 for exactly this reason — "with no {@code comments} table an
 * identifier cannot be checked and a moderator would open a report with nothing behind
 * it" — and this class is the check that was missing, published from the community
 * module's application layer rather than by letting the moderation module read
 * {@code comments} itself.
 *
 * <p>{@code PublicProjects} is the model, down to the shape of the refusal: each
 * module answers for its own surface, and a second copy of "which comments may a
 * stranger see" is the copy that forgets the tombstone the next time the rule changes.
 *
 * <p>Deliberately not a method on {@link CommentService}. That class is the tab's own
 * use cases and every entry point on it begins by establishing a caller; this one has
 * no caller to establish and answers a question about the object alone. Folding a
 * cross-module read into a service whose every other method starts with an
 * authorisation check is how the cross-module read eventually inherits the wrong one.
 */
@Service
public class PublicComments {

    private final CommentRepository comments;
    private final PublicProjects projects;

    public PublicComments(CommentRepository comments, PublicProjects projects) {
        this.comments = comments;
        this.projects = projects;
    }

    /**
     * Establishes that there is a comment there worth complaining about.
     *
     * <p><strong>A removed comment is deliberately unreportable</strong>, which reads
     * oddly for a safety feature until you notice what a tombstone is: the comment is
     * already off the page, so a further report about it adds a row to the queue and no
     * information. {@code ReportTargets} makes the identical argument about a suspended
     * campaign, and the reporter gets the same 404 anybody browsing gets, which is
     * consistent rather than special. A report filed <em>before</em> the removal stays
     * open and stays readable — V25 keeps the row and its body for exactly that.
     *
     * <p>The campaign is checked as well, through {@link PublicProjects}, so that a
     * comment under a draft or suspended campaign cannot be used to find out that the
     * campaign is there.
     *
     * @throws CommentNotFoundException when there is nothing there this caller is
     *     allowed to know exists
     */
    @Transactional(readOnly = true)
    public void requireReportable(UUID commentId) {
        Comment comment = comments.findById(commentId).orElseThrow(() -> new CommentNotFoundException(commentId));
        if (comment.isDeleted()) {
            throw new CommentNotFoundException(commentId);
        }
        try {
            projects.requireVisible(comment.getProjectId());
        } catch (ProjectNotFoundException e) {
            throw new CommentNotFoundException(commentId);
        }
    }
}
