package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.community.domain.Comment;
import az.ideanest.community.domain.ReplyDepthExceededException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shape of a thread, and what removing a comment does to it.
 *
 * <p>No database and no HTTP. {@link Comment} decides the depth rule, the thread a
 * reply belongs to, and the tombstone; those are the rules the feature is, and each of
 * them is worth failing on its own line rather than inside a 422 from an endpoint that
 * also had to authenticate.
 *
 * <p>The same rules are enforced again by V25 — {@code CommentSchemaTests} is that half
 * — and by the API — {@code CommentApiTests} is that one. Three layers is not three
 * copies of one test: this one says the rule exists, the schema one says it survives a
 * support script, and the API one says the right person is refused.
 */
class CommentThreadingTests {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID SOMEBODY_ELSE = UUID.randomUUID();

    @Nested
    @DisplayName("a root")
    class Roots {

        @Test
        @DisplayName("heads its own thread")
        void headsItsOwnThread() {
            // Not merely "has a thread": the identifier has to be its own, or the
            // reply query -- WHERE thread_id IN (the roots on this page) -- returns
            // nothing for it and the conversation loses its answers.
            Comment root = Comment.root(PROJECT, AUTHOR, "Will this ship to Baku?", false);
            assertThat(root.getThreadId()).isEqualTo(root.getId());
            assertThat(root.getParentId()).isNull();
            assertThat(root.getDepth()).isZero();
            assertThat(root.isRoot()).isTrue();
        }

        @Test
        @DisplayName("accepts replies")
        void acceptsReplies() {
            assertThat(Comment.root(PROJECT, AUTHOR, "Anything?", false).acceptsReplies())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a reply")
    class Replies {

        @Test
        @DisplayName("joins its parent's thread and campaign rather than being told which")
        void inheritsTheThread() {
            // The campaign is taken from the parent and is not a parameter, which is
            // what makes "answer one campaign's comment under another campaign"
            // unrepresentable rather than merely refused.
            Comment root = Comment.root(PROJECT, AUTHOR, "Will this ship to Baku?", false);
            Comment reply = Comment.replyTo(root, SOMEBODY_ELSE, "Yes, from March.", true);

            assertThat(reply.getProjectId()).isEqualTo(PROJECT);
            assertThat(reply.getThreadId()).isEqualTo(root.getId());
            assertThat(reply.getParentId()).isEqualTo(root.getId());
            assertThat(reply.getDepth()).isEqualTo(1);
            assertThat(reply.isRoot()).isFalse();
        }

        @Test
        @DisplayName("cannot be replied to")
        void isTheEndOfTheLine() {
            // The depth rule, in the domain rather than in the UI. A client that
            // offers the control anyway gets this, not a third level.
            Comment root = Comment.root(PROJECT, AUTHOR, "Will this ship to Baku?", false);
            Comment reply = Comment.replyTo(root, SOMEBODY_ELSE, "Yes, from March.", true);

            assertThat(reply.acceptsReplies()).isFalse();
            assertThatThrownBy(() -> Comment.replyTo(reply, AUTHOR, "Thanks.", false))
                    .isInstanceOf(ReplyDepthExceededException.class)
                    .extracting(exception -> ((ReplyDepthExceededException) exception).maxDepth())
                    .isEqualTo(Comment.MAX_DEPTH);
        }
    }

    @Nested
    @DisplayName("the creator highlight")
    class CreatorHighlight {

        @Test
        @DisplayName("is carried from the write, on a root and on a reply alike")
        void isCarriedFromTheWrite() {
            // C-02. The value arrives from CommentService, which got it from
            // ProjectAccess; this class only has to keep it. Nothing here computes
            // it, and that is the property worth pinning: a Comment that could work
            // out its own answer would be a second place the rule lives.
            Comment root = Comment.root(PROJECT, AUTHOR, "An update on the moulds.", true);
            Comment reply = Comment.replyTo(root, SOMEBODY_ELSE, "Thanks for the answer.", false);

            assertThat(root.isByCreator()).isTrue();
            assertThat(reply.isByCreator()).isFalse();
        }
    }

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("tombstones rather than emptying: the text stays for the moderator, the flag stays for the page")
        void tombstones() {
            Comment comment = Comment.root(PROJECT, AUTHOR, "Something regrettable.", false);
            Instant when = Instant.parse("2026-08-18T09:00:00Z");

            assertThat(comment.deleteBy(AUTHOR, when)).isTrue();
            assertThat(comment.isDeleted()).isTrue();
            assertThat(comment.getDeletedAt()).isEqualTo(when);
            assertThat(comment.getDeletedBy()).isEqualTo(AUTHOR);
            // Kept, deliberately. V25: a report about a removed comment is the
            // evidence that removing it was right, and blanking the row on delete
            // would let somebody erase what they were reported for.
            assertThat(comment.getBody()).isEqualTo("Something regrettable.");
        }

        @Test
        @DisplayName("a second deletion changes nothing, so a retry cannot rewrite who removed it")
        void isIdempotent() {
            // Two moderators reaching the same conclusion a second apart must not
            // make the audit row from the first one describe a moment this column
            // then contradicts.
            Comment comment = Comment.root(PROJECT, AUTHOR, "Something regrettable.", false);
            Instant first = Instant.parse("2026-08-18T09:00:00Z");
            comment.deleteBy(AUTHOR, first);

            assertThat(comment.deleteBy(SOMEBODY_ELSE, first.plusSeconds(60))).isFalse();
            assertThat(comment.getDeletedBy()).isEqualTo(AUTHOR);
            assertThat(comment.getDeletedAt()).isEqualTo(first);
        }

        @Test
        @DisplayName("a removed root still heads its thread, so its replies are not orphaned")
        void doesNotOrphanTheThread() {
            // The requirement, stated as an assertion. Nothing about the replies
            // changes, and the root is still the row `thread_id` points at -- which
            // is what makes the conversation readable after its opening line goes.
            Comment root = Comment.root(PROJECT, AUTHOR, "Will this ship to Baku?", false);
            Comment reply = Comment.replyTo(root, SOMEBODY_ELSE, "Yes, from March.", true);

            root.deleteBy(AUTHOR, Instant.parse("2026-08-18T09:00:00Z"));

            assertThat(reply.getThreadId()).isEqualTo(root.getId());
            assertThat(reply.isDeleted()).isFalse();
            assertThat(reply.getBody()).isEqualTo("Yes, from March.");
        }
    }
}
