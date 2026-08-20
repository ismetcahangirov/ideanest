package az.ideanest.pledge.application;

/**
 * A backer filter the report cannot answer: an unreported state, a destination that is
 * not a country code, or a collection longer than the bound.
 *
 * <p>A 400 in every case, and the message is written for a person because the only thing
 * that raises it is what the caller sent. It is <strong>not</strong> an
 * {@link IllegalArgumentException} subtype, so that a narrow advice can catch it without
 * also demoting an internal invariant failure to a bad request —
 * {@code AnalyticsExceptionHandler} explains the trap.
 */
public class InvalidBackerFilterException extends RuntimeException {

    public InvalidBackerFilterException(String message) {
        super(message);
    }
}
