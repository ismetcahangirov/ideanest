package az.ideanest.shared.observability;

import java.time.Instant;

/**
 * What the last ledger reconciliation found, answered by the module that ran it — §18, issue
 * #138.
 *
 * <p>{@link QueueDepthSource}'s shape, for {@link QueueDepthSource}'s reason. §8.4 asks for an
 * alert on ledger imbalance, and the reconciliation that decides whether there is one lives in
 * the payment module — which {@code shared} may not depend on, and which
 * {@code ModuleBoundaryTests} enforces. So the asking side names this interface and the owning
 * side implements it: a metric appears by adding a bean, and this package needs no edit.
 *
 * <h2>BOTH ANSWERS, AND THE SECOND ONE IS THE ONE PEOPLE FORGET</h2>
 *
 * "How many findings" is the alert everybody writes. "When did it last run" is the one that
 * catches the worse failure: a reconciliation that has silently stopped looks exactly like a
 * platform whose books balance, and an alert on the finding count alone would stay green
 * through it for ever. {@code alerts.yml} fires on both.
 */
public interface ReconciliationStatusSource {

    /** How many discrepancies the last pass found. Zero for a pass that found none. */
    int findings();

    /**
     * When the last pass ran, or {@code null} if none has since this process started.
     *
     * <p>Null rather than the epoch, because "a very old reconciliation" and "no
     * reconciliation on this replica yet" are different facts and a metric that reported an
     * age of fifty-six years for the second would page somebody during every deploy. The
     * binder publishes no age at all until there is one.
     */
    Instant lastRunAt();
}
