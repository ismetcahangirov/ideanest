package az.ideanest.pledgemanager.domain;

/**
 * An address the platform will not store — §4.8's PM-07.
 *
 * <p>Carries the field so the form can highlight it. A postal address is eight boxes
 * and a refusal that does not say which one is a refusal a backer answers by
 * re-reading all of them.
 *
 * <p><strong>The message never contains the value.</strong> It says "this is longer
 * than 200 characters", not what was typed: an exception message reaches the logs,
 * and the whole point of V36 is that a street address is not in them.
 */
public class AddressInvalidException extends RuntimeException {

    private final String field;

    public AddressInvalidException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** Which of the eight, as the client names it. */
    public String field() {
        return field;
    }
}
