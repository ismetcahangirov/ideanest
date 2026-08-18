package az.ideanest.community.application;

import az.ideanest.community.domain.Comment;
import java.util.List;
import java.util.UUID;

/**
 * One conversation: a top-level comment and the replies under it.
 *
 * <p>Because the thread is two levels (V25), this is the whole shape — there is no
 * tree to walk and no recursion in any client.
 *
 * @param root the comment that started it. Possibly a tombstone: a removed root keeps
 *     its place so the replies under it are not orphaned
 * @param replies oldest first, because a conversation reads forwards even though the
 *     list of conversations is newest first
 * @param nextReplyCursor what to send as {@code ?thread=<root>&cursor=} to read on,
 *     or null when these are all of them. <strong>Null rather than a count of what is
 *     left</strong>: a count is a second aggregate over rows the query has already
 *     seen, and a client only ever needs to know whether there is a "show more" and
 *     what to ask for
 */
public record CommentThread(Comment root, List<Comment> replies, UUID nextReplyCursor) {

    public CommentThread {
        replies = List.copyOf(replies);
    }
}
