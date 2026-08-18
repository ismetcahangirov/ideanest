package az.ideanest.shared.jobs;

/**
 * How far a job is from being given up on.
 *
 * <p>Two states and no third one. There is deliberately no {@code RUNNING}: a
 * process killed between setting it and clearing it would leave it behind for ever,
 * and nothing can tell that from a job that is genuinely running. Who holds a job
 * now is a lease with an expiry, which answers the question and expires on its own.
 */
public enum JobState {

    /** Eligible, once its backoff has elapsed and nobody else holds the lease. */
    READY,

    /**
     * Given up on, after the configured number of consecutive failures.
     *
     * <p>Terminal until an operator says otherwise — V20 carries the statement. A job
     * that has failed eight times in a row, each attempt further apart than the last,
     * is waiting for a person rather than for the network, and retrying it for ever
     * buries the one fact that matters in a log line repeated a million times.
     */
    DEAD
}
