package az.ideanest.payment.application;

import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.payment.infrastructure.PaymentTransactionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.11's AD-05 payment log, read — #304.
 *
 * <p>Every charge the platform has attempted, its provider reference, and why the refused
 * ones were refused. It is the read half of a table {@code CollectionRun} writes and
 * nothing updates, so there is no consistency question here at all: a row that exists said
 * the same thing yesterday and will say it next year.
 *
 * <p><strong>No authorisation and no audit row here.</strong> Both live in
 * {@code admin.application.PaymentLogService}, one layer out, for the reason
 * {@code AuditTrail} gives: the module that owns the table publishes the read, and the
 * module that owns the console decides who may take it and records that they did. Putting
 * the staff check here would put {@code shared.access} in front of the collection run as
 * well, which has no caller to check.
 *
 * <p><strong>It is a projection boundary as much as a service.</strong> The rows leave here
 * as {@link LoggedTransaction}, which is what lets the admin module read this table without
 * naming a {@code domain} type — see that record for why that rule is doing real work
 * rather than being satisfied for form's sake.
 */
@Service
public class PaymentLog {

    private final PaymentTransactionRepository transactions;

    public PaymentLog(PaymentTransactionRepository transactions) {
        this.transactions = transactions;
    }

    /**
     * One page of the log, newest first.
     *
     * @param scope one of {@link PaymentLogScope}'s three shapes. Normalised first, so the
     *     page and the echoed scope describe the same query
     * @param before the last identifier of the previous page, or null for the first
     * @param limit already clamped by the caller
     */
    @Transactional(readOnly = true)
    public PaymentLogPage page(PaymentLogScope scope, UUID before, int limit) {
        PaymentLogScope asked = scope.normalised();
        PageRequest page = PageRequest.ofSize(limit);
        List<PaymentTransaction> rows = rowsFor(asked, before, page);

        // A full page is the only honest signal that there may be more; the report queue
        // and the audit trail both take the same line, and for the same reason.
        UUID nextCursor = rows.size() < limit ? null : rows.get(rows.size() - 1).getId();
        return new PaymentLogPage(asked, rows.stream().map(LoggedTransaction::of).toList(), nextCursor);
    }

    private List<PaymentTransaction> rowsFor(PaymentLogScope scope, UUID before, PageRequest page) {
        if (scope.pledgeId() != null) {
            return before == null
                    ? transactions.newestOfPledge(scope.pledgeId(), page)
                    : transactions.newestOfPledgeBefore(scope.pledgeId(), before, page);
        }
        if (scope.projectId() != null) {
            return before == null
                    ? transactions.newestOfProject(scope.projectId(), page)
                    : transactions.newestOfProjectBefore(scope.projectId(), before, page);
        }
        return before == null ? transactions.newest(page) : transactions.newestBefore(before, page);
    }
}
