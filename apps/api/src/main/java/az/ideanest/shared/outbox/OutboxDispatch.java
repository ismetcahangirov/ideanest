package az.ideanest.shared.outbox;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One event, one transaction: claim it, publish it, record that it went.
 *
 * <p><strong>This class is where the delivery guarantee is actually decided</strong>, and
 * it is three sentences long because everything difficult is inside the claim — see
 * {@link OutboxEventRepository#claimNext}. The row is locked with {@code FOR UPDATE SKIP
 * LOCKED}, so no second relay can be looking at it; the dispatch happens while that lock
 * is held; and the outcome is written by the transaction that holds it.
 *
 * <p><strong>Dispatch, then commit — which is at-least-once and never at-most-once.</strong>
 * If the process dies between the transport accepting the message and this transaction
 * committing, the row is still {@code PENDING} and the event is published again. That
 * duplicate is a deliberate choice: the other order — commit first, then dispatch — turns
 * every crash into a lost event, and nothing can tell afterwards that anything was lost.
 * A duplicate is visible to the consumer and can be deduplicated on
 * {@link OutboxMessage#id()}; a loss is visible to nobody.
 *
 * <p><strong>One row per transaction, not a batch.</strong> The same argument
 * {@code ReservationExpiry} makes: a batch in one transaction has the wrong failure mode in
 * both directions. A rollback after twenty successful dispatches would republish all
 * twenty, and a commit covering rows whose dispatch failed would mark them published. Round
 * one row, the claim and the outcome are the same unit of work, which is the invariant that
 * matters — a row that says {@code PUBLISHED} was handed to the transport, and a row that
 * does not will be handed to it again.
 *
 * <p>A separate bean from {@link OutboxRelay} rather than a method on it, because Spring's
 * {@code @Transactional} is a proxy and a self-call carries no transaction at all — which,
 * for an argument entirely about where the transaction boundary is, would leave the boundary
 * nowhere.
 *
 * <p><strong>The dispatch target is a parameter rather than an injected collaborator</strong>,
 * so that the relay decides what the transport is and this class decides nothing but the
 * transaction. It also means a test can hand in a transport that refuses without replacing
 * a bean — and replacing a bean would give that test a Spring context of its own, and with
 * it a second PostgreSQL container, which is what {@code AbstractIntegrationTest} exists to
 * avoid.
 */
@Component
public class OutboxDispatch {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatch.class);

    /**
     * How much of a failure is worth keeping. Long enough for a provider's error body,
     * short enough that a stack trace in a text column does not become the table.
     */
    private static final int LONGEST_RECORDED_ERROR = 1000;

    private final OutboxEventRepository events;
    private final OutboxProperties properties;

    public OutboxDispatch(OutboxEventRepository events, OutboxProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    /** What one pass over one event did. */
    public enum Outcome {

        /** The transport accepted it, and the row says so. */
        PUBLISHED,

        /**
         * The transport refused it. The row is either waiting for its next attempt or, if
         * that was the last one, a dead letter.
         */
        FAILED,

        /**
         * There was nothing this relay could take: an empty queue, everything backing
         * off, everything blocked behind an earlier event for its own aggregate, or every
         * candidate already locked by another relay. All four are the same instruction —
         * stop, and look again next tick.
         */
        NOTHING_TO_DO
    }

    /**
     * Publishes the next eligible event, if there is one this relay can have.
     *
     * @param now the instant to judge eligibility and backoff against. Passed in rather
     *     than read from a clock here so that a test can ask what happens after the
     *     backoff without waiting for it, and so that every row in one pass is judged
     *     against one moment
     * @param target where the event goes
     */
    @Transactional
    public Outcome dispatchNext(Instant now, OutboxDispatcher target) {
        OutboxEvent event = events.claimNext(now).orElse(null);
        if (event == null) {
            return Outcome.NOTHING_TO_DO;
        }

        try {
            target.dispatch(event.asMessage());
        } catch (RuntimeException e) {
            recordFailure(event, now, e);
            return Outcome.FAILED;
        }

        event.published(now);
        return Outcome.PUBLISHED;
    }

    /**
     * Counts the attempt and decides whether there is another one.
     *
     * <p>Caught rather than propagated, because the exception is the transport's answer
     * and not this transaction's failure: letting it out would roll back the very record
     * that says the attempt happened, and the event would be retried immediately, for
     * ever, with {@code attempts} never moving off zero.
     */
    private void recordFailure(OutboxEvent event, Instant now, RuntimeException failure) {
        int attempt = event.getAttempts() + 1;
        String reason = describe(failure);

        if (attempt >= properties.maxAttempts()) {
            event.deadLetter(now, reason);
            // ERROR, and it should page somebody: this is an event the platform recorded
            // as having happened and will now never tell anybody about. The identifier is
            // the row, so an operator can read the payload and decide what to do.
            log.error(
                    "Outbox event {} ({} on {} {}) is a dead letter after {} attempts: {}",
                    event.getId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    attempt,
                    reason);
            return;
        }

        event.retryAfter(now, properties.backoffAfter(attempt), reason);
        // WARN rather than ERROR: a refused dispatch is the case this design exists to
        // absorb, and the event is still the platform's outstanding promise.
        log.warn(
                "Outbox event {} ({}) was not accepted on attempt {} of {}; next attempt after {}: {}",
                event.getId(),
                event.getEventType(),
                attempt,
                properties.maxAttempts(),
                event.getNextAttemptAt(),
                reason);
    }

    /**
     * The failure, as a sentence somebody can act on.
     *
     * <p>The type and the message, not the stack: the stack is in the log for the attempt
     * that produced it, and what a dead letter needs to carry is which transport said no
     * and what it said.
     */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String described = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return described.length() <= LONGEST_RECORDED_ERROR
                ? described
                : described.substring(0, LONGEST_RECORDED_ERROR);
    }
}
