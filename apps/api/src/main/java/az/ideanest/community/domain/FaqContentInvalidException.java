package az.ideanest.community.domain;

/**
 * An FAQ entry whose question or answer the platform will not store.
 *
 * <p>Names the field, so the refusal can be shown beside the input that caused it
 * rather than as a banner over a form the creator has just spent ten minutes filling
 * in.
 */
public class FaqContentInvalidException extends RuntimeException {

    private final String field;

    public FaqContentInvalidException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** {@code question} or {@code answer}, as the request body spells it. */
    public String field() {
        return field;
    }
}
