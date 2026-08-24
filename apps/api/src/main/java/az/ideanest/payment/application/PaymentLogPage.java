package az.ideanest.payment.application;

import java.util.List;
import java.util.UUID;

/**
 * One page of the payment log, newest first.
 *
 * @param scope what was asked for, carried back so a client cannot file a stale response
 *     under the wrong filter
 * @param transactions the matching calls, newest first — a log is read from the end, which
 *     is the opposite of the report queue and for the reason {@code AuditTrailPage} gives
 * @param nextCursor the last row's identifier, to send as {@code after} for the next page,
 *     or null when this was the last one. No total: counting an append-only table is a
 *     scan for a number that is stale before it renders
 */
public record PaymentLogPage(PaymentLogScope scope, List<LoggedTransaction> transactions, UUID nextCursor) {

    public PaymentLogPage {
        transactions = List.copyOf(transactions);
    }
}
