package az.ideanest.community.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One thing one account said under a campaign. §4.4's Comments tab, §4.9's C-01 to
 * C-03.
 *
 * <h2>The thread is two levels, and this class is where that is true</h2>
 *
 * <p>A comment is a <strong>root</strong> ({@code depth} 0, no parent, heading its own
 * thread) or a <strong>reply</strong> ({@code depth} 1, parent a root, in the root's
 * thread). {@link #MAX_DEPTH} is 1 and {@link #replyTo} refuses anything deeper with
 * {@link ReplyDepthExceededException} — so the rule is enforced here rather than in a
 * controller or a client, and a second write path added later inherits it instead of
 * having to remember it. V25 states the same bound as a check constraint and, with
 * {@code comments_reply_hangs_below_its_parent}, makes it a real tree rather than a
 * column that claims to be one.
 *
 * <p>Why two levels and not an arbitrary tree is V25's header: it is a read plan, a
 * moderation surface, and a thing a phone can render. §10.2 gives the feature one
 * reply route and no notion of replying to a reply, which is the same conclusion from
 * the other direction.
 *
 * <h2>{@link #isByCreator()} is a fact about the moment, not a claim</h2>
 *
 * <p>Passed in by {@code CommentService} from the authorisation that was actually in
 * force when the comment was written, never sent by a client and never recomputed on
 * read. A collaborator whose grant is revoked next month wrote for the campaign this
 * month, and a highlight derived on read would quietly say otherwise.
 *
 * <h2>Deleting is a tombstone</h2>
 *
 * <p>{@link #deleteBy} sets {@code deleted_at} and {@code deleted_by} and touches
 * nothing else. The row stays, keeping the replies under a deleted root attached to
 * something and keeping an open report about it resolvable; the body stays on the row
 * as the evidence a moderator has to read, and no read path serves it. V25 has the
 * argument at length.
 *
 * <p>Everything else is immutable: there is no setter for the body and §10.2 gives a
 * comment no edit endpoint. Editing is how a comment somebody replied to becomes a
 * different comment after the fact.
 */
@Entity
@Table(name = "comments")
public class Comment {

    /**
     * The deepest a comment may sit: 0 for a root, 1 for a reply, and nothing below
     * that.
     *
     * <p>Also {@code comments_depth_bounded} in V25. See the class comment.
     */
    public static final int MAX_DEPTH = 1;

    /** A root's depth, named rather than written as a bare 0 in four places. */
    public static final int ROOT_DEPTH = 0;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null for a root. Equivalent to {@code depth == 0}, which V25 states as a constraint. */
    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    /** The root of this conversation; this row's own identifier for a root. */
    @Column(name = "thread_id", nullable = false, updatable = false)
    private UUID threadId;

    @Column(name = "depth", nullable = false, updatable = false)
    private short depth;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "by_creator", nullable = false, updatable = false)
    private boolean byCreator;

    /** Set once, by {@link #deleteBy}. Updatable, and the only thing about a comment that is. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    /**
     * The database's, through a default, so a comment cannot claim to have been
     * written at a time the application chose — and because the pages are keyset by a
     * UUID v7 that has to agree with it. {@link Generated} is what makes it readable
     * in the request that wrote it.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Comment() {
        // JPA.
    }

    private Comment(
            UUID projectId, UUID parentId, UUID threadId, short depth, UUID authorId, String body, boolean byCreator) {

        this.id = Identifiers.newIdentifier();
        this.projectId = projectId;
        this.parentId = parentId;
        // A root heads its own thread. Assigned from the identifier this constructor
        // just minted rather than by the caller, so the two cannot disagree.
        this.threadId = threadId == null ? this.id : threadId;
        this.depth = depth;
        this.authorId = authorId;
        this.body = body;
        this.byCreator = byCreator;
    }

    /**
     * A new top-level comment on a campaign.
     *
     * @param authorId the authenticated caller, never a value from a request body
     * @param byCreator whether this account was acting for the campaign when they
     *     wrote it. Established by {@code CommentService} from {@code ProjectAccess};
     *     see the class comment for why it is stored and not derived
     */
    public static Comment root(UUID projectId, UUID authorId, String body, boolean byCreator) {
        Objects.requireNonNull(projectId, "A comment belongs to a campaign");
        Objects.requireNonNull(authorId, "A comment names who wrote it");
        return new Comment(projectId, null, null, (short) ROOT_DEPTH, authorId, CommentBody.of(body), byCreator);
    }

    /**
     * A reply to {@code parent}.
     *
     * <p>The campaign and the thread are taken from the parent rather than from the
     * caller: a reply is in its parent's conversation by construction, so there is no
     * argument a caller could get wrong and no way to answer one campaign's comment
     * under another campaign.
     *
     * @throws ReplyDepthExceededException when {@code parent} is itself a reply. See
     *     the class comment, and V25's header for why the bound exists at all
     */
    public static Comment replyTo(Comment parent, UUID authorId, String body, boolean byCreator) {
        Objects.requireNonNull(parent, "A reply answers a comment");
        Objects.requireNonNull(authorId, "A comment names who wrote it");
        if (parent.depth >= MAX_DEPTH) {
            throw new ReplyDepthExceededException(MAX_DEPTH);
        }
        return new Comment(
                parent.projectId,
                parent.id,
                parent.threadId,
                (short) (parent.depth + 1),
                authorId,
                CommentBody.of(body),
                byCreator);
    }

    /**
     * Tombstones this comment.
     *
     * <p>Idempotent on purpose: deleting an already-deleted comment leaves the first
     * deletion's time and hand alone. A retry, or two moderators reaching the same
     * conclusion a second apart, must not rewrite who removed it — the {@code
     * audit_logs} row from the first removal names a moment this column would then
     * contradict.
     *
     * @param actorId the author withdrawing their own comment, or whoever on the
     *     campaign's team removed it. Which of the two it was is answerable
     *     afterwards by comparing this with {@link #getAuthorId()}
     * @return whether this call is the one that removed it
     */
    public boolean deleteBy(UUID actorId, Instant when) {
        Objects.requireNonNull(actorId, "A deletion names who did it");
        Objects.requireNonNull(when, "A deletion happened at a time");
        if (deletedAt != null) {
            return false;
        }
        this.deletedAt = when;
        this.deletedBy = actorId;
        return true;
    }

    /** Whether this comment has been removed. A tombstone is still a row and is still served as one. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Whether this comment heads a conversation rather than answering one. */
    public boolean isRoot() {
        return depth == ROOT_DEPTH;
    }

    /** Whether a reply may be made to this comment. False for a reply; see the class comment. */
    public boolean acceptsReplies() {
        return depth < MAX_DEPTH;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public UUID getThreadId() {
        return threadId;
    }

    public int getDepth() {
        return depth;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    /**
     * What was written.
     *
     * <p><strong>Never put on a response for a deleted comment.</strong> The row keeps
     * it because a moderator reading a report has to see what was complained about;
     * the tab does not. {@code CommentResponse} is the one place that decides, and it
     * decides from {@link #isDeleted()}.
     */
    public String getBody() {
        return body;
    }

    /** C-02's highlight: the campaign itself said this. */
    public boolean isByCreator() {
        return byCreator;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Comment comment && Objects.equals(id, comment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No body. It is text one person wrote about another and this lands in logs,
        // which §17.4 keeps that out of.
        return "Comment[id=" + id + ", project=" + projectId + ", depth=" + depth + ", deleted=" + isDeleted() + "]";
    }
}
