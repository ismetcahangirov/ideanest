package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database refuses about a comment.
 *
 * <p>Every rule here is also enforced in Java. That is not duplication, for
 * {@code ProjectUpdateSchemaTests}' reason: an application check is enforced by
 * whichever code path remembered to call it, and a constraint is enforced against a
 * migration, a support query, a bulk import, and a bug.
 *
 * <p><strong>The one that matters most is
 * {@link #aReplyCannotHangUnderAnotherReply()}.</strong> The depth rule is the shape of
 * the whole feature — the read plan, the moderation surface and every client's renderer
 * assume two levels — and a {@code CHECK (depth BETWEEN 0 AND 1)} on its own does
 * <em>not</em> enforce it: a writer that claims depth 1 while pointing at a depth-1
 * parent satisfies the check and produces a three-level tree with a column lying about
 * it. {@code comments_reply_hangs_below_its_parent} is what closes that, and this is the
 * test that says so.
 *
 * <p>Deliberately not {@code @Transactional}: a statement that violates a constraint
 * aborts the surrounding transaction, so each of these needs its own. A
 * {@link JdbcTemplate} against an auto-committing connection gives exactly that.
 */
class CommentSchemaTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the accounts these tests create.
     *
     * <p>A counter rather than a slice of the identifier, for
     * {@code ProjectUpdateSchemaTests}' reason: UUID version 7 begins with a
     * millisecond timestamp, so two accounts created in the same millisecond share
     * their leading digits.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @AfterEach
    void clearProjects() {
        // Comments cascade from projects, which one of these also exercises.
        // Deleted explicitly anyway, because a cleanup that relies on a cascade
        // stops working the day the cascade is reconsidered and nothing says why.
        jdbc().update("DELETE FROM comments");
        jdbc().update("DELETE FROM projects");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "comment-schema-" + SEQUENCE.incrementAndGet();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        marker + "@example.com",
                        "Test Person",
                        marker);
        return id;
    }

    private UUID insertProject(UUID creatorId) {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO projects (id, creator_id, slug, title) VALUES (?, ?, ?, ?)",
                        id,
                        creatorId,
                        "campaign-" + SEQUENCE.incrementAndGet(),
                        "A campaign");
        return id;
    }

    /** A root, inserted exactly as the application does: its own identifier as its thread. */
    private UUID insertRoot(UUID projectId, UUID authorId) {
        UUID id = Identifiers.newIdentifier();
        insert(id, projectId, null, id, 0, authorId, "A question about shipping.", false);
        return id;
    }

    private UUID insertReply(UUID projectId, UUID parentId, UUID threadId, int depth, UUID authorId) {
        UUID id = Identifiers.newIdentifier();
        insert(id, projectId, parentId, threadId, depth, authorId, "An answer.", false);
        return id;
    }

    private int insert(
            UUID id,
            UUID projectId,
            UUID parentId,
            UUID threadId,
            int depth,
            UUID authorId,
            String body,
            boolean byCreator) {

        return jdbc().update(
                        """
                        INSERT INTO comments (id, project_id, parent_id, thread_id, depth, author_id, body, by_creator)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        id,
                        projectId,
                        parentId,
                        threadId,
                        depth,
                        authorId,
                        body,
                        byCreator);
    }

    // -----------------------------------------------------------------------
    // The shape of the thread
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a reply cannot hang under another reply, even when it claims to be at depth 1")
    void aReplyCannotHangUnderAnotherReply() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);
        UUID reply = insertReply(project, root, root, 1, author);

        // The row satisfies `comments_depth_bounded` -- it says depth 1 -- and it is
        // in the right thread. What refuses it is the composite foreign key: the
        // parent one level up from a depth-1 row is a depth-0 row, and `reply` is
        // not one. Without this the tree would be three levels deep and every
        // renderer and the whole read plan would be wrong about it.
        assertThatThrownBy(() -> insertReply(project, reply, root, 1, author))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a depth of two is refused outright")
    void depthIsBounded() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);
        UUID reply = insertReply(project, root, root, 1, author);

        assertThatThrownBy(() -> insertReply(project, reply, root, 2, author))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a reply cannot claim to be in a thread other than its parent's")
    void aReplyStaysInItsParentsThread() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID first = insertRoot(project, author);
        UUID second = insertRoot(project, author);

        // Otherwise a reply could be attached to one conversation and read as part
        // of another -- the page fetches replies by thread_id, so the answer would
        // appear under a question nobody asked.
        assertThatThrownBy(() -> insertReply(project, first, second, 1, author))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a root has no parent, and a comment with a parent is not a root")
    void rootsAndRepliesAreNotInterchangeable() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);

        // "Depth 0 with a parent" and "depth 1 with none" are the two incoherent
        // rows, and the constraint is written as an equivalence so neither can be
        // introduced by fixing the other.
        UUID orphan = Identifiers.newIdentifier();
        assertThatThrownBy(() -> insert(orphan, project, root, root, 0, author, "Confused.", false))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID parentless = Identifiers.newIdentifier();
        assertThatThrownBy(() -> insert(parentless, project, null, parentless, 1, author, "Confused.", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a root heads its own thread and a reply never does")
    void threadIdentityIsConsistent() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        // A root pointing at somebody else's thread would be a conversation with two
        // openings, and the page would list it twice.
        UUID stranger = insertRoot(project, author);
        UUID confused = Identifiers.newIdentifier();
        assertThatThrownBy(() -> insert(confused, project, null, stranger, 0, author, "Confused.", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Content
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a body of whitespace is refused, including whitespace that is not a space")
    void bodiesAreNotBlank() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        // NOT NULL would accept both. The second is the one `!~ '^\s*$'` exists for:
        // PostgreSQL's one-argument btrim removes spaces and nothing else.
        UUID spaces = Identifiers.newIdentifier();
        assertThatThrownBy(() -> insert(spaces, project, null, spaces, 0, author, "   ", false))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID newlines = Identifiers.newIdentifier();
        assertThatThrownBy(() -> insert(newlines, project, null, newlines, 0, author, "\n\t", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a body stops one character past the limit")
    void bodiesAreBounded() {
        UUID author = insertUser();
        UUID project = insertProject(author);

        UUID atTheLimit = Identifiers.newIdentifier();
        assertThatCode(() -> insert(atTheLimit, project, null, atTheLimit, 0, author, "c".repeat(5000), false))
                .doesNotThrowAnyException();

        UUID pastIt = Identifiers.newIdentifier();
        assertThatThrownBy(() -> insert(pastIt, project, null, pastIt, 0, author, "c".repeat(5001), false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // The tombstone
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a deletion has both a time and a hand, or neither")
    void deletionsAreCoherent() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);

        // "Removed by nobody" and "removed at no time" are rows nobody can explain
        // to the person asking why their comment is gone.
        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE comments SET deleted_at = now() WHERE id = ?", root))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE comments SET deleted_by = ? WHERE id = ?", author, root))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc().update(
                        "UPDATE comments SET deleted_at = now(), deleted_by = ? WHERE id = ?", author, root))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a comment cannot have been removed before it was written")
    void deletionsFollowTheComment() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);

        assertThatThrownBy(() -> jdbc().update(
                        "UPDATE comments SET deleted_at = ?, deleted_by = ? WHERE id = ?",
                        OffsetDateTime.ofInstant(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC),
                        author,
                        root))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("removing a root leaves its replies where they are")
    void aTombstonedRootKeepsItsReplies() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);
        insertReply(project, root, root, 1, author);

        jdbc().update("UPDATE comments SET deleted_at = now(), deleted_by = ? WHERE id = ?", author, root);

        // The requirement, at the level that actually enforces it: a tombstone is an
        // UPDATE, so the cascade on parent_id never fires and nothing is orphaned.
        assertThat(jdbc().queryForObject(
                        "SELECT count(*) FROM comments WHERE thread_id = ?", Long.class, root))
                .isEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // The campaign
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("hard deleting a campaign takes its whole thread with it")
    void commentsCascadeFromTheCampaign() {
        UUID author = insertUser();
        UUID project = insertProject(author);
        UUID root = insertRoot(project, author);
        insertReply(project, root, root, 1, author);

        jdbc().update("DELETE FROM projects WHERE id = ?", project);

        // A campaign that can still be hard deleted is one that never launched, so
        // nobody read the conversation under it. Nothing here is worth orphaning.
        assertThat(jdbc().queryForObject(
                        "SELECT count(*) FROM comments WHERE project_id = ?", Long.class, project))
                .isZero();
    }
}
