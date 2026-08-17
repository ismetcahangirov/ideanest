package az.ideanest.shared.outbox;

/**
 * Where an event is between being recorded and being somebody else's problem.
 *
 * <p>Three states, and V19's {@code outbox_events_state_known} refuses a fourth. The
 * transitions are {@code PENDING -> PUBLISHED} and {@code PENDING -> DEAD}, and
 * neither is reversible: an event that has been dispatched cannot be un-dispatched,
 * and one that has been abandoned is abandoned deliberately rather than temporarily.
 */
public enum OutboxEventState {

    /**
     * Recorded, not yet delivered.
     *
     * <p>The only state the relay looks at, and the only one that blocks a later event
     * for the same aggregate — which is what makes ordering per aggregate hold without
     * a lock held across dispatches.
     */
    PENDING,

    /**
     * Handed to the dispatch target, which accepted it.
     *
     * <p>Not "the consumer processed it". The outbox's guarantee ends at the
     * transport; what happens after it is the consumer's own business, which is why
     * every consumer needs to be idempotent rather than to be told twice not to be.
     */
    PUBLISHED,

    /**
     * The attempts ran out. Nothing will try again.
     *
     * <p><strong>A deliberate state and not a failure of the design.</strong> An event
     * that has been refused the same way eight times is waiting for a person, not for
     * the network, and retrying it for ever would spend a request per poll for ever
     * and bury the one fact that matters — that this event was never delivered — in a
     * log line repeated a million times. A row in this state is a question for an
     * operator, carrying the error that produced it.
     *
     * <p>It also unblocks its aggregate, which is a decision worth naming: the events
     * behind a dead letter are dispatched, so one poisoned message does not stop a
     * campaign's traffic for ever. Ordering still holds over everything that was
     * actually published, because a dead letter never is.
     */
    DEAD
}
