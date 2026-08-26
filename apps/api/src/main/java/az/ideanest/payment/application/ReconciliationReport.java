package az.ideanest.payment.application;

import java.time.Instant;
import java.util.List;

/**
 * What one reconciliation pass found — issue #70.
 *
 * <p><strong>An empty list is the answer, not a missing one.</strong> A pass that found nothing
 * is a pass that ran and checked, which is a different fact from a pass that never happened —
 * and the difference is the whole reason {@link #runAt} is on the record. A reconciliation that
 * silently stops running looks exactly like a platform whose books balance.
 *
 * @param runAt when this pass ran
 * @param accountsChecked how many account-and-currency positions were read. Zero on a platform
 *     that has taken no money, which is balanced rather than unchecked
 * @param findings everything wrong, in the order the checks asked
 */
public record ReconciliationReport(Instant runAt, int accountsChecked, List<ReconciliationFinding> findings) {

    /** Whether this pass found nothing wrong. */
    public boolean balanced() {
        return findings.isEmpty();
    }

    /** A platform that has never been reconciled, for whatever reads this before the first pass. */
    public static ReconciliationReport neverRun() {
        return new ReconciliationReport(null, 0, List.of());
    }

    /** Whether a pass has ever run. See {@link #neverRun()} for why that is worth asking. */
    public boolean hasRun() {
        return runAt != null;
    }
}
