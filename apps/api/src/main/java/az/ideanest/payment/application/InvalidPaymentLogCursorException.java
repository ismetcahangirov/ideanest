package az.ideanest.payment.application;

/**
 * A paging cursor {@code /v1/admin/payments} did not produce — issue #412.
 *
 * <p>A 400 rather than the first page, for the reason {@link PaymentLogCursor#decode} gives: a
 * client paging wrongly would otherwise look like one that had reached the end, and an operator
 * reconciling a collection run would be handed the top of the log in place of the attempts they
 * had not read.
 *
 * <p><strong>Deliberately not an {@link IllegalArgumentException}.</strong>
 * {@code ConsoleExceptionHandler} maps that type to "no such ledger account" for the three
 * console read surfaces this endpoint is one of, so inheriting it would answer a malformed
 * cursor with a refusal about a ledger the caller never mentioned. The same trap
 * {@code InvalidAuditCursorException} names, and the same way out.
 *
 * <p>Carries no message. There is nothing to say that the status and the code do not already
 * say, and naming which half of the value was wrong would tell whoever is probing how the value
 * is built.
 */
public class InvalidPaymentLogCursorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidPaymentLogCursorException() {
        super("The payment log cursor is not one this endpoint produced");
    }
}
