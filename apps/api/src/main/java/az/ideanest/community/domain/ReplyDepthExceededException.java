package az.ideanest.community.domain;

/**
 * Somebody tried to reply to a reply.
 *
 * <p>A domain exception rather than a validation message, because the rule is
 * {@link Comment#MAX_DEPTH} and is the shape of the thread itself — see
 * {@code Comment} for why the shape is two levels. Raised where the tree is built,
 * so every write path inherits it rather than each remembering to check.
 *
 * <p>Carries the bound so the refusal can state it. A client that is told "replies go
 * one level deep" can hide the control; one that is told "no" cannot.
 */
public class ReplyDepthExceededException extends RuntimeException {

    private final int maxDepth;

    public ReplyDepthExceededException(int maxDepth) {
        super("A reply can only be made to a top-level comment.");
        this.maxDepth = maxDepth;
    }

    /** The deepest a comment may sit. 1, meaning a root and one level of replies. */
    public int maxDepth() {
        return maxDepth;
    }
}
