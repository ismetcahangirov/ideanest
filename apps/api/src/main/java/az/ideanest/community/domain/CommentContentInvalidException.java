package az.ideanest.community.domain;

/**
 * A comment whose body the platform will not store.
 *
 * <p>Names the field, for {@link UpdateContentInvalidException}'s reason: the refusal
 * is shown beside the box somebody has just typed into.
 */
public class CommentContentInvalidException extends RuntimeException {

    private final String field;

    public CommentContentInvalidException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** {@code body}, as the request spells it. One field today; named anyway, so the shape does not change when there are two. */
    public String field() {
        return field;
    }
}
