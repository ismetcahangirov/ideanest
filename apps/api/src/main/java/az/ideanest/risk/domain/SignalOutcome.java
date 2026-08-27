package az.ideanest.risk.domain;

/**
 * What happened when a signal was evaluated — issue #108.
 *
 * <p><strong>Three outcomes and not two, and the third is the point.</strong> A signal
 * that could not be evaluated is not a signal that found nothing. A score of 12 with every
 * signal clear means the platform looked and is satisfied; a score of 12 with two signals
 * unavailable means it looked at what it could. Collapsing the two would make the queue
 * lie in the direction that costs money.
 */
public enum SignalOutcome {

    /** Evaluated, and it found something. Its weight is in the score. */
    FIRED,

    /** Evaluated, and there was nothing. Contributes nothing. */
    CLEAR,

    /**
     * Not evaluated, because the platform has no way to.
     *
     * <p>Contributes nothing to the score and is counted separately, so that a reader can
     * tell a clean assessment from a partial one.
     */
    UNAVAILABLE
}
