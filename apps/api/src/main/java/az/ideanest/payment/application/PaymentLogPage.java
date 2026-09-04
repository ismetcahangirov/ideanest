package az.ideanest.payment.application;

import java.util.List;

/**
 * One page of the payment log, newest first.
 *
 * @param scope what was asked for, carried back so a client cannot file a stale response
 *     under the wrong filter
 * @param transactions the matching calls, newest first — a log is read from the end, which
 *     is the opposite of the report queue and for the reason {@code AuditTrailPage} gives
 * @param nextCursor where the last row on this page sits, to send as {@code after} for the
 *     next page, or null when this was the last one. An instant and an identifier rather than
 *     an identifier alone since #412, because the log is ordered by a column that is not
 *     unique — {@link PaymentLogCursor} carries why. No total: counting an append-only table
 *     is a scan for a number that is stale before it renders
 */
public record PaymentLogPage(
        PaymentLogScope scope, List<LoggedTransaction> transactions, PaymentLogCursor nextCursor) {

    public PaymentLogPage {
        transactions = List.copyOf(transactions);
    }
}
