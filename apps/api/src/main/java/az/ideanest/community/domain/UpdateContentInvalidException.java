package az.ideanest.community.domain;

/**
 * An update whose title or body the platform will not store.
 *
 * <p>Names the field, so the refusal can be shown beside the input that caused it
 * rather than as a banner over a form the creator has just spent ten minutes filling
 * in.
 */
public class UpdateContentInvalidException extends RuntimeException {

    private final String field;

    public UpdateContentInvalidException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** {@code title} or {@code body}, as the request body spells it. */
    public String field() {
        return field;
    }
}
