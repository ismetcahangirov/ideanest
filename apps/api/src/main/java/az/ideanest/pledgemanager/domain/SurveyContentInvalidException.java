package az.ideanest.pledgemanager.domain;

/**
 * A survey or a question the platform will not store — §4.8's PM-01 to PM-03.
 *
 * <p>Carries the field, like {@code AddressInvalidException} and for the same reason:
 * the builder is a form with a dozen controls on it, and a refusal that does not say
 * which one is answered by re-reading all of them.
 *
 * <p>Every case it carries is one V35 also refuses. The duplication is the point — the
 * database is what makes a bad row impossible, and this is what makes the refusal a
 * sentence somebody can act on.
 */
public class SurveyContentInvalidException extends RuntimeException {

    private final String field;

    public SurveyContentInvalidException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** Which control, as the client names it. */
    public String field() {
        return field;
    }
}
