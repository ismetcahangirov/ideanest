package az.ideanest.audit;

/**
 * A paging cursor {@code /v1/admin/audit} did not produce.
 *
 * <p>A 400 rather than the first page, for the reason {@link AuditCursor#decode} gives: a
 * client paging wrongly would otherwise look like one that had reached the end, and an
 * investigator would be handed the top of the log again in place of the part they had not
 * read.
 *
 * <p><strong>Deliberately not an {@link IllegalArgumentException}.</strong>
 * {@code ConsoleExceptionHandler} maps that type to "no such ledger account" for the three
 * console read surfaces this endpoint is one of, so inheriting it would answer a malformed
 * cursor with a refusal about a ledger the caller never mentioned.
 *
 * <p>Carries no message. There is nothing to say that the status and the code do not
 * already say, and naming which half of the value was wrong would tell whoever is probing
 * how the value is built.
 */
public class InvalidAuditCursorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidAuditCursorException() {
        super("The audit trail cursor is not one this endpoint produced");
    }
}
