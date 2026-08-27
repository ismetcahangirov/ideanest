package az.ideanest.payment.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * §4.11's AD-05, the half nobody could see — issue #106.
 *
 * <h2>The gap this closes</h2>
 *
 * #70 built the reconciliation and #138 gave it a gauge, and between them the answer to
 * "do the platform's books balance?" was reachable in exactly two places: a log line at
 * 02:30, and a Prometheus scrape. Neither is where the person who has to act on it works.
 * The console has a payment log, a ledger, a payout queue, refunds and chargebacks — every
 * financial operation except the one that says whether the sum of them is right.
 *
 * <p>That is what made #106 still open with AD-05, AD-06 and AD-07 all built: "financial
 * operations tooling" had the four capabilities the issue names and not the one that
 * checks them.
 *
 * <h2>Reading it and running it are two different authorities on purpose</h2>
 *
 * <p>Both need {@link StaffCapability#VIEW_FINANCE} and neither needs more, which is a
 * deliberate choice rather than an oversight. A reconciliation pass is two aggregate
 * queries and it <strong>writes nothing</strong> — {@link LedgerReconciliation} reports and
 * never repairs, because the correcting entry depends on which of a dozen things went
 * wrong and a job that guessed would turn a detectable problem into an undetectable one.
 * So the worst a caller can do with {@link #runNow} is spend two queries, and gating it
 * behind an approval authority would mean the person who noticed a discrepancy needs
 * somebody else before they can confirm it.
 *
 * <p>Running one <em>is</em> audited even so. It is what somebody does when they suspect
 * the books are wrong, and "who last checked, and when" is the question asked afterwards.
 *
 * <h2>Why the last report is not read from a table</h2>
 *
 * {@link LedgerReconciliationJob} holds it in memory and says why: it is regenerated every
 * night, and a row would be a schema, a migration and a retention rule for something with
 * a lifetime of one day. That decision is kept, and it has one honest consequence this
 * service has to carry rather than hide — <strong>the last report is this replica's</strong>.
 * Two replicas reconcile the same books and either answer is the truth, but a console
 * request lands on one of them, so a fleet that has just been redeployed answers "never
 * run" until the next nightly pass. {@link #runNow} is the way out of that, and is the
 * reason it exists at all.
 */
@Service
public class ReconciliationService {

    private final LedgerReconciliationJob job;
    private final PlatformStaff staff;
    private final AuditLog audit;

    public ReconciliationService(LedgerReconciliationJob job, PlatformStaff staff, AuditLog audit) {
        this.job = job;
        this.staff = staff;
        this.audit = audit;
    }

    /**
     * The most recent pass this replica made.
     *
     * <p>Not audited, unlike the ledger and the payment log. Those name campaigns, pledges
     * and people; this is a count of findings and a timestamp, and there is nothing in it
     * whose reader is worth a row. §22.1's argument for recording who read the ledger is
     * about the rows, not about the arithmetic over them.
     *
     * @return the last report, or {@link ReconciliationReport#neverRun()} — which is a
     *     different fact from a balanced one, and the response says which
     */
    public ReconciliationReport latest(UUID staffId) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);
        return job.lastReport();
    }

    /**
     * One pass, now, because somebody asked.
     *
     * <p>The result replaces the held report, so a member of finance who runs one and then
     * refreshes sees what they just ran rather than last night's.
     */
    public ReconciliationReport runNow(UUID staffId) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);

        ReconciliationReport report = job.reconcileNow();

        /*
         * `recordIndependently`, following ConsoleReadService: there is no write to be
         * atomic with, and the row must commit whether or not the response reaches the
         * client. The detail is the outcome rather than the findings -- a finding carries
         * figures, and audit_logs has no retention rule.
         */
        audit.recordIndependently(
                AuditAction.LEDGER_RECONCILED,
                staffId,
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "accountsChecked=%d; findings=%d".formatted(report.accountsChecked(), report.findings().size()));

        return report;
    }
}
