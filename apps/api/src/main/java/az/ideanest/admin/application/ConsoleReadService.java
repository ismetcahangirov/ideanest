package az.ideanest.admin.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.audit.AuditTrail;
import az.ideanest.audit.AuditTrailFilter;
import az.ideanest.audit.AuditTrailPage;
import az.ideanest.ledger.application.LedgerReader;
import az.ideanest.ledger.application.LedgerScope;
import az.ideanest.ledger.application.LedgerView;
import az.ideanest.payment.application.PaymentLog;
import az.ideanest.payment.application.PaymentLogPage;
import az.ideanest.payment.application.PaymentLogScope;
import az.ideanest.shared.access.PlatformStaff;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The console's three read-only surfaces — AD-05 and AD-14, issues #304, #305 and #314.
 *
 * <h2>What this class is, given that it holds no data</h2>
 *
 * <p>Every row these three endpoints return belongs to somebody else: the trail is the
 * audit package's, the charges are the payment module's, the postings are the ledger's.
 * What is left is exactly what administration <em>is</em> — the authorisation, the audit
 * row, and the ordering between them — which is the argument
 * {@link UserAdministrationService} makes about AD-04 and the reason this is a module
 * rather than three endpoints scattered across the modules that own the tables.
 *
 * <h2>One class for three surfaces, and not three</h2>
 *
 * <p>They differ only in which read they delegate to. Split into three, the staff check
 * and the audit call would be written three times, and the failure mode of that is not
 * hypothetical: the fourth surface somebody adds is the one that copies the check and
 * forgets the record. Written once, a new console read is a method that cannot be written
 * without both.
 *
 * <h2>Every read is recorded, which almost no read on this platform is</h2>
 *
 * <p>{@link AuditAction#ACCOUNTS_SEARCHED} carries the general argument and these three
 * inherit it: an account with no relationship to a pledge, a campaign or a person is
 * reading what they paid, when, and what was done to them. "Who looked at this" cannot be
 * asked afterwards of a read nobody recorded, and the answer matters most in exactly the
 * situation where nobody thought to switch recording on.
 *
 * <p>Recorded through {@code recordIndependently} rather than {@code record}, following
 * {@code BackerExportService}: there is no write to be atomic with, and the row must commit
 * whether or not the response reaches the client. An over-record beats a gap in the one
 * table that answers this question.
 *
 * <p>The detail carries the filter and the number of rows and never a row. The audit trail
 * would otherwise double in size every time somebody read it, and the payment log would
 * copy a card's decline history into a table that cannot be pruned.
 */
@Service
public class ConsoleReadService {

    private final AuditTrail trail;
    private final PaymentLog payments;
    private final LedgerReader ledger;
    private final PlatformStaff staff;
    private final AuditLog audit;

    public ConsoleReadService(
            AuditTrail trail, PaymentLog payments, LedgerReader ledger, PlatformStaff staff, AuditLog audit) {

        this.trail = trail;
        this.payments = payments;
        this.ledger = ledger;
        this.staff = staff;
        this.audit = audit;
    }

    /**
     * AD-14's trail: what has been done, by whom, to what.
     *
     * @throws az.ideanest.project.application.NotAModeratorException for a caller who is not
     *     platform staff
     */
    public AuditTrailPage auditTrail(UUID staffId, AuditTrailFilter filter, UUID before, int limit) {
        staff.requireStaff(staffId);
        AuditTrailPage page = trail.page(filter, before, limit);

        record(
                staffId,
                AuditAction.AUDIT_TRAIL_READ,
                "rows=%d; entityType=%s; entity=%s; actor=%s"
                        .formatted(
                                page.entries().size(),
                                page.filter().entityType(),
                                present(page.filter().entityId()),
                                present(page.filter().actorId())));

        return page;
    }

    /**
     * AD-05's payment log: every charge, its provider reference, and why it failed.
     *
     * @throws az.ideanest.project.application.NotAModeratorException for a caller who is not
     *     platform staff
     */
    public PaymentLogPage paymentLog(UUID staffId, PaymentLogScope scope, UUID before, int limit) {
        staff.requireStaff(staffId);
        PaymentLogPage page = payments.page(scope, before, limit);

        record(
                staffId,
                AuditAction.PAYMENT_LOG_READ,
                "rows=%d; pledge=%s; project=%s"
                        .formatted(
                                page.transactions().size(),
                                present(page.scope().pledgeId()),
                                present(page.scope().projectId())));

        return page;
    }

    /**
     * AD-05's ledger: both sides of every posting, and what each account holds.
     *
     * @throws az.ideanest.project.application.NotAModeratorException for a caller who is not
     *     platform staff
     */
    public LedgerView ledger(UUID staffId, LedgerScope scope, Long before, int limit) {
        staff.requireStaff(staffId);
        LedgerView view = ledger.page(scope, before, limit);

        record(
                staffId,
                AuditAction.LEDGER_READ,
                "postings=%d; account=%s; project=%s"
                        .formatted(
                                view.postings().size(),
                                scope.account() == null ? null : scope.account().name(),
                                present(scope.projectId())));

        return view;
    }

    private void record(UUID staffId, AuditAction action, String detail) {
        // The entity is the staff account rather than a subject, following
        // ACCOUNTS_SEARCHED: a read of a list has no single subject, which is exactly what
        // makes it worth recording.
        audit.recordIndependently(action, staffId, AuditActor.moderator(staffId), AuditOutcome.SUCCEEDED, detail);
    }

    /**
     * Whether a filter named a thing, without naming it.
     *
     * <p>An identifier is not personal data on its own, and it is a join key to a table
     * that holds plenty; this column has no retention rule, so what goes in it is that the
     * filter was used rather than what it was set to. The same line
     * {@link UserAdministrationService} draws around a search term.
     */
    private static String present(UUID value) {
        return value == null ? "none" : "set";
    }
}
