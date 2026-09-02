package az.ideanest.payment.api;

import az.ideanest.payment.application.ReconciliationFinding;
import az.ideanest.payment.application.ReconciliationReport;
import az.ideanest.payment.application.ReconciliationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-05, over HTTP: do the platform's books add up? — issue #106.
 *
 * <p>Two calls, and the second exists because of what the first cannot promise. The last
 * report is held in the process that made it ({@code LedgerReconciliationJob} says why it
 * is not a table), so a console request lands on one replica and may be told "never run"
 * on a fleet that was redeployed this morning. Running one is two aggregate queries that
 * write nothing, so the way out of that is to ask for a fresh answer rather than to build
 * a table for a value with a lifetime of one day.
 *
 * <p>{@code no-store}, for the reason every console route gives and one of its own: this
 * is the number somebody reads when they think money has gone missing, and a cached copy
 * of "balanced" is the single worst thing to serve them.
 */
@RestController
@RequestMapping("/v1/admin/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /** The most recent pass this replica made. */
    @GetMapping
    public ResponseEntity<Report> latest(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Report.of(reconciliation.latest(callerOf(accessToken))));
    }

    /**
     * Runs one now.
     *
     * <p>{@code POST /runs} rather than {@code POST} on the collection above, because what
     * is created is a pass and not a reconciliation: the plural names the thing that
     * accumulates, even though only the newest is kept. Answers 200 with the report rather
     * than 202 — the pass is synchronous and the caller is waiting for the answer, and a
     * 202 would tell them to come back for something they already have.
     */
    @PostMapping("/runs")
    public ResponseEntity<Report> run(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Report.of(reconciliation.runNow(callerOf(accessToken))));
    }

    /**
     * One pass, as the console reads it.
     *
     * @param hasRun whether a pass has ever happened on this replica. <strong>The field
     *     that stops the screen lying.</strong> A reconciliation that silently stopped
     *     running looks exactly like a platform whose books balance, and without this the
     *     two are one response: {@code balanced: true, findings: []}
     * @param runAt when, or null when {@code hasRun} is false
     * @param accountsChecked how many account-and-currency positions were read. Zero on a
     *     platform that has taken no money, which is balanced rather than unchecked
     * @param balanced whether the pass found nothing
     * @param findings everything wrong, in the order the three checks asked
     */
    public record Report(
            boolean hasRun, Instant runAt, int accountsChecked, boolean balanced, List<Finding> findings) {

        static Report of(ReconciliationReport report) {
            return new Report(
                    report.hasRun(),
                    report.runAt(),
                    report.accountsChecked(),
                    report.balanced(),
                    report.findings().stream().map(Finding::of).toList());
        }
    }

    /**
     * One thing that is wrong.
     *
     * <p><strong>The parts travel as well as the sentence — issue #403.</strong> The console
     * renders these to a person in one of four languages, and {@code detail} is English prose
     * with a raw account identifier and an unformatted amount in it. It was the only text on
     * that screen the reader could not read. So the console builds its own sentence from
     * {@code code}, {@code account} and the amounts, and {@code detail} stays because it is
     * what the log line prints and what an operator reading a stack of alerts wants.
     *
     * @param kind which of the three questions was answered wrongly. What a screen groups
     *     on, and what an alert routes on
     * @param code which of the four checks produced it, and therefore which sentence it is.
     *     Finer than {@code kind}, because two of the four are {@code IMPOSSIBLE_SIGN} and
     *     say opposite things
     * @param account the ledger account, or null for a finding about a whole currency
     * @param currency §21.2 refuses to add two, so a finding is about exactly one
     * @param amount the figure the sentence turns on, as a string, per §10.3 — a JSON number
     *     is an IEEE 754 double and this is money
     * @param otherAmount the second figure, for the finding that compares two. Null elsewhere
     * @param detail the sentence, with the figures in it, in English
     */
    public record Finding(
            ReconciliationFinding.Kind kind,
            ReconciliationFinding.Code code,
            String account,
            String currency,
            String amount,
            String otherAmount,
            String detail) {

        static Finding of(ReconciliationFinding finding) {
            return new Finding(
                    finding.kind(),
                    finding.code(),
                    finding.account(),
                    finding.currency(),
                    finding.amount().toPlainString(),
                    finding.otherAmount() == null ? null : finding.otherAmount().toPlainString(),
                    finding.detail());
        }
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
