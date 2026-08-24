package az.ideanest.shared.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Outbox rows, by the one question the relay asks of them. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The next event this relay may dispatch, locked so that no other relay can.
     *
     * <p><strong>One statement, because everything difficult about this is decided
     * inside it.</strong> A claim written as a read followed by an update cannot be
     * made correct: two relays read the same row, both conclude it is theirs, and both
     * publish — and the second publication is not a duplicate anybody chose, it is a
     * duplicate nobody can see. So the claim is the lock, and PostgreSQL is what
     * serialises it. This is the same argument as
     * {@code IdempotencyRecordRepository#findByAccountIdAndIdempotencyKey} makes about
     * its unique index, and as {@code RewardTierRepository#reserveOnePlace} makes about
     * its conditional update: the condition that makes a race safe lives in a statement
     * the database serialises, never in a check the application performed a moment ago.
     *
     * <p><strong>{@code SKIP LOCKED} and not a wait.</strong> A relay that queued behind
     * the lock would acquire it the instant the holder committed — and then publish a
     * row that had just been published, because the state it decided on was read before
     * the wait. Skipping is what makes several instances a throughput improvement rather
     * than a correctness problem: they divide the queue between them and never meet.
     *
     * <p><strong>The {@code NOT EXISTS} is the ordering guarantee.</strong> An event is
     * only a candidate when no earlier {@code PENDING} event for the same aggregate
     * exists, so a consumer cannot be told a pledge was collected before it was told the
     * pledge was confirmed. Three consequences, all deliberate:
     *
     * <ul>
     *   <li>A row being dispatched right now is still {@code PENDING} — its transaction
     *       has not committed — so the events behind it are not candidates, and the
     *       order holds without any relay holding a lock across dispatches.
     *   <li>A row backing off after a failure blocks its own aggregate until it goes
     *       out. That is the point: the alternative is delivering the second event and
     *       then the first.
     *   <li>A {@code DEAD} row does <em>not</em> block, because it will never be
     *       published at all. One poisoned event must not stop a campaign's traffic for
     *       ever, and the order over everything actually published is still correct.
     * </ul>
     *
     * <p>Nothing here blocks across aggregates, which is the other half of the same
     * statement: ordering is guaranteed within an aggregate, and there is no order
     * between two different pledges to get wrong.
     *
     * <p>Native rather than JPQL because JPQL cannot express {@code SKIP LOCKED} at all
     * — {@code PESSIMISTIC_WRITE} with a timeout of zero fails instead of skipping,
     * which turns a busy second relay into an exception rather than a relay with nothing
     * to do.
     */
    @Query(
            value =
                    """
                    SELECT o.* FROM outbox_events o
                     WHERE o.state = 'PENDING'
                       AND o.next_attempt_at <= :now
                       AND NOT EXISTS (
                           SELECT 1 FROM outbox_events earlier
                            WHERE earlier.aggregate_type = o.aggregate_type
                              AND earlier.aggregate_id = o.aggregate_id
                              AND earlier.state = 'PENDING'
                              AND earlier.sequence_no < o.sequence_no)
                     ORDER BY o.sequence_no
                     LIMIT 1
                     FOR UPDATE OF o SKIP LOCKED
                    """,
            nativeQuery = true)
    Optional<OutboxEvent> claimNext(@Param("now") Instant now);

    /**
     * How many events are in one state — AD-16's queue depth, issue #316.
     *
     * <p>A {@code COUNT} over a partial-index-free column, which is deliberate: the table
     * is kept small by the relay draining it, and the one deployment where it is not is
     * exactly the one this screen exists to show. An index added for a count nobody runs
     * in a loop would cost every insert on the platform's busiest write path.
     *
     * <p>Read through {@link OutboxQueueDepth} rather than directly, so that the health
     * screen never learns this table exists.
     */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.state = :state")
    long countByState(@Param("state") OutboxEventState state);
}
