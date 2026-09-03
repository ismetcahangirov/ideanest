package az.ideanest.payment.application;

/**
 * The payment log was asked for an outcome that is not one — #404.
 *
 * <p>{@code ?status=refunded} is the shape of it: a plausible word that names no row, because
 * a refund is a {@code type} and never a {@code status}. Refused rather than ignored, for the
 * reason {@link PaymentLogScope#outcome()} gives — a filter that is silently dropped answers a
 * wider question than the one asked, under a heading that still says otherwise.
 *
 * <p>An {@code IllegalArgumentException} would have been the smaller change and it is the wrong
 * one: {@code AdminUserExceptionHandler} already maps that type to a refusal about suspending
 * an account, and the two advices overlap on this controller's package. A named exception is
 * what lets {@code ConsoleExceptionHandler} answer this with a code a client can branch on.
 */
public class UnknownTransactionOutcomeException extends RuntimeException {

    private final String asked;

    public UnknownTransactionOutcomeException(String asked) {
        super("No such transaction outcome: " + asked);
        this.asked = asked;
    }

    /** What was asked for, so the refusal can quote it back. It came from the query, not a row. */
    public String asked() {
        return asked;
    }
}
