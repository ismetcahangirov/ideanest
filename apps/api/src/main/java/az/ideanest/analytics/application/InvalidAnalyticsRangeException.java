package az.ideanest.analytics.application;

/**
 * A range of days the analytics read will not answer.
 *
 * <p>A named type rather than a bare {@link IllegalArgumentException}, and that is not
 * tidiness. {@code CurrencyMismatchException} is also an {@code IllegalArgumentException}
 * and its own comment says it must surface as a 500 — a mixture of currencies is a fault
 * in the platform, not something the client can fix by sending a different query. An
 * advice that mapped the supertype to 400 would turn that fault into a tidy bad request
 * and hide it, which is the failure {@code ReferralExceptionHandler} warns about when it
 * confines its own broad catch to one controller.
 *
 * <p>So this is the one thing the analytics range endpoint reports as the caller's, and
 * the message is written for a person because the caller can act on it.
 */
public class InvalidAnalyticsRangeException extends IllegalArgumentException {

    public InvalidAnalyticsRangeException(String message) {
        super(message);
    }
}
