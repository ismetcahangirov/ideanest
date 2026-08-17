package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxDispatch;
import az.ideanest.shared.outbox.OutboxDispatch.Outcome;
import az.ideanest.shared.outbox.OutboxDispatcher;
import az.ideanest.shared.outbox.OutboxMessage;
import az.ideanest.shared.outbox.OutboxProperties;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The guarantee (#135): a database commit and its published event cannot diverge.
 *
 * <p>Five of these carry the design, and they are the ones CLAUDE.md §3 names as not
 * optional — state transitions and idempotency.
 *
 * <ul>
 *   <li>{@link #aRolledBackBusinessWriteTakesItsEventWithIt()} is the whole issue. The
 *       event is a row written by the same transaction as the business change, so
 *       there is no instant at which one exists without the other.
 *   <li>{@link #aFailedPublishLeavesTheRowRetryable()} is at-least-once: a transport
 *       that refused the message leaves a pending row with a later attempt time, not
 *       a lost event.
 *   <li>{@link #concurrentRelaysDoNotDoublePublish()} is {@code FOR UPDATE SKIP
 *       LOCKED}, checked against two real connections rather than described.
 *   <li>{@link #retryExhaustionReachesTheTerminalState()} is the dead letter: a
 *       poisoned event stops being retried for ever and stops blocking its aggregate.
 *   <li>{@link #perAggregateOrderingHolds()} is the ordering claim, including the part
 *       that matters — a pending head blocks its own aggregate and nothing else.
 * </ul>
 *
 * <p>Every dispatch here is driven directly with the instant it should be judged
 * against, for the reason {@code ReservationCleanerJob} gives: the relay's timer is
 * set to {@code -} in the test profile, because a poll firing in the background would
 * publish the very rows these tests are about to assert on.
 */
class OutboxTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the accounts these tests create. A counter rather than a slice of
     * the identifier: UUID version 7 begins with a millisecond timestamp, so two
     * accounts created in the same millisecond would collide on the unique email index
     * for a reason that has nothing to do with what is being checked.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String AGGREGATE = "pledge";

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxDispatch dispatch;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Clock clock;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactions);
    }

    @AfterEach
    void clearOutbox() {
        jdbc().update("DELETE FROM outbox_events");
        jdbc().update("DELETE FROM users WHERE slug LIKE 'outbox-%'");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** The instant these tests measure from. The clock, so a frozen one is respected. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** One event, enqueued in a transaction of its own, as a business write would. */
    private UUID enqueue(UUID aggregateId, String eventType) {
        return inTransaction()
                .execute(status -> outbox.record(AGGREGATE, aggregateId, eventType, Map.of("of", eventType)));
    }

    /** A business write, so that "the same transaction" has something to be the same as. */
    private String insertUser() {
        String marker = "outbox-" + SEQUENCE.incrementAndGet();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        Identifiers.newIdentifier(),
                        marker + "@example.com",
                        "A backer",
                        marker);
        return marker;
    }

    private boolean userExists(String slug) {
        Integer count =
                jdbc().queryForObject("SELECT count(*) FROM users WHERE slug = ?", Integer.class, slug);
        return count != null && count > 0;
    }

    private String stateOf(UUID eventId) {
        return jdbc().queryForObject("SELECT state FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private int attemptsOf(UUID eventId) {
        Integer attempts =
                jdbc().queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, eventId);
        return attempts == null ? 0 : attempts;
    }

    private Instant nextAttemptAt(UUID eventId) {
        return jdbc().queryForObject(
                "SELECT next_attempt_at FROM outbox_events WHERE id = ?", Instant.class, eventId);
    }

    private long eventCount() {
        Long count = jdbc().queryForObject("SELECT count(*) FROM outbox_events", Long.class);
        return count == null ? 0 : count;
    }

    /** A dispatcher that remembers what it was handed and always accepts it. */
    private static final class Recording implements OutboxDispatcher {

        private final List<OutboxMessage> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void dispatch(OutboxMessage message) {
            messages.add(message);
        }

        List<UUID> identifiers() {
            return messages.stream().map(OutboxMessage::id).toList();
        }
    }

    /** A transport that is down. The failure the outbox exists to survive. */
    private static final OutboxDispatcher REFUSING = message -> {
        throw new IllegalStateException("the transport is down");
    };

    // -----------------------------------------------------------------------
    // The guarantee
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a committed business write and its event are both there")
    void aCommittedBusinessWriteKeepsItsEvent() {
        String marker = inTransaction().execute(status -> {
            String slug = insertUser();
            outbox.record(AGGREGATE, Identifiers.newIdentifier(), "pledge.confirmed", Map.of("slug", slug));
            return slug;
        });

        assertThat(userExists(marker)).isTrue();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled back business write takes its event with it")
    void aRolledBackBusinessWriteTakesItsEventWithIt() {
        // This is the issue, in one assertion. An event published from a listener
        // after the commit is lost by a crash in the window between the two; an event
        // published before it is a message about something that never happened. The
        // row is written by the same transaction, so neither window exists.
        assertThatThrownBy(() -> inTransaction().execute(status -> {
                    String slug = insertUser();
                    outbox.record(AGGREGATE, Identifiers.newIdentifier(), "pledge.confirmed", Map.of("slug", slug));
                    throw new IllegalStateException("the business write failed after the event was recorded");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(eventCount()).isZero();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM users WHERE slug LIKE 'outbox-%'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("recording an event without a transaction is refused")
    void recordingAnEventWithoutATransactionIsRefused() {
        // MANDATORY rather than REQUIRED. A caller with no transaction of its own has
        // nothing for the event to be atomic with, and starting one here would write a
        // row that commits on its own — which is the divergence this package exists to
        // prevent, arrived at by the convenience of not having to say so.
        assertThatThrownBy(() ->
                        outbox.record(AGGREGATE, Identifiers.newIdentifier(), "pledge.confirmed", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(eventCount()).isZero();
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a dispatched event is published once and carries its own identifier")
    void aDispatchedEventIsPublishedOnce() {
        UUID eventId = enqueue(Identifiers.newIdentifier(), "pledge.confirmed");
        Recording target = new Recording();

        assertThat(dispatch.dispatchNext(now(), target)).isEqualTo(Outcome.PUBLISHED);
        assertThat(stateOf(eventId)).isEqualTo("PUBLISHED");
        // The stable identifier consumers deduplicate on: the row's own primary key,
        // not something minted per delivery.
        assertThat(target.identifiers()).containsExactly(eventId);

        // And it is not offered a second time.
        assertThat(dispatch.dispatchNext(now(), target)).isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(target.identifiers()).containsExactly(eventId);
    }

    @Test
    @DisplayName("a failed publish leaves the row retryable, and redelivers the same identifier")
    void aFailedPublishLeavesTheRowRetryable() {
        UUID eventId = enqueue(Identifiers.newIdentifier(), "pledge.confirmed");
        Instant firstAttempt = now();

        assertThat(dispatch.dispatchNext(firstAttempt, REFUSING)).isEqualTo(Outcome.FAILED);

        assertThat(stateOf(eventId)).isEqualTo("PENDING");
        assertThat(attemptsOf(eventId)).isEqualTo(1);
        assertThat(nextAttemptAt(eventId)).isAfter(firstAttempt);

        // Backoff: not before the row says so.
        Recording target = new Recording();
        assertThat(dispatch.dispatchNext(firstAttempt, target)).isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(target.identifiers()).isEmpty();

        Instant afterBackoff = nextAttemptAt(eventId);
        assertThat(dispatch.dispatchNext(afterBackoff, target)).isEqualTo(Outcome.PUBLISHED);
        assertThat(stateOf(eventId)).isEqualTo("PUBLISHED");
        // At-least-once, and the reason handlers have to tolerate redelivery: the same
        // event may be dispatched more than once, and it is the same event when it is.
        assertThat(target.identifiers()).containsExactly(eventId);
    }

    @Test
    @DisplayName("retry exhaustion reaches the terminal state and stays there")
    void retryExhaustionReachesTheTerminalState() {
        UUID eventId = enqueue(Identifiers.newIdentifier(), "pledge.confirmed");
        Instant attemptAt = now();

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            assertThat(dispatch.dispatchNext(attemptAt, REFUSING))
                    .withFailMessage("attempt %d should have been offered", attempt)
                    .isEqualTo(Outcome.FAILED);
            // Far enough forward that the backoff has certainly elapsed. The point of
            // this test is the count of attempts, not the arithmetic of the delay.
            attemptAt = attemptAt.plus(Duration.ofDays(1));
        }

        assertThat(stateOf(eventId)).isEqualTo("DEAD");
        assertThat(attemptsOf(eventId)).isEqualTo(properties.maxAttempts());
        assertThat(jdbc().queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, eventId))
                .contains("the transport is down");

        // Terminal means terminal: no clock reaches it again.
        Recording target = new Recording();
        assertThat(dispatch.dispatchNext(attemptAt.plus(Duration.ofDays(365)), target))
                .isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(target.identifiers()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Ordering, and not double-publishing
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("events reach the transport in order within an aggregate")
    void perAggregateOrderingHolds() {
        UUID pledge = Identifiers.newIdentifier();
        UUID other = Identifiers.newIdentifier();

        UUID first = enqueue(pledge, "pledge.drafted");
        UUID second = enqueue(pledge, "pledge.confirmed");
        UUID third = enqueue(pledge, "pledge.collected");
        UUID unrelated = enqueue(other, "pledge.drafted");

        Recording target = new Recording();
        assertThat(relay.publishPending(now(), target)).isEqualTo(4);

        // Interleaving with the other aggregate is permitted; overtaking within one is
        // not. A collection announced before the confirmation it followed would have a
        // consumer build a state that never existed.
        assertThat(target.identifiers()).contains(unrelated);
        assertThat(target.identifiers()).containsSubsequence(first, second, third);
    }

    @Test
    @DisplayName("a pending head blocks its own aggregate and nothing else")
    void aPendingHeadBlocksOnlyItsOwnAggregate() {
        UUID pledge = Identifiers.newIdentifier();
        UUID other = Identifiers.newIdentifier();

        UUID head = enqueue(pledge, "pledge.drafted");
        UUID behind = enqueue(pledge, "pledge.confirmed");
        UUID unrelated = enqueue(other, "pledge.drafted");

        Instant attemptAt = now();
        assertThat(dispatch.dispatchNext(attemptAt, REFUSING)).isEqualTo(Outcome.FAILED);
        assertThat(stateOf(head)).isEqualTo("PENDING");

        // The event behind the failed one waits for it. Everything else does not: a
        // single stuck aggregate must not stop the platform's other traffic.
        Recording target = new Recording();
        assertThat(relay.publishPending(attemptAt, target)).isEqualTo(1);
        assertThat(target.identifiers()).containsExactly(unrelated);
        assertThat(stateOf(behind)).isEqualTo("PENDING");

        // Once the head goes, the queue moves, and in order.
        assertThat(relay.publishPending(nextAttemptAt(head), target)).isEqualTo(2);
        assertThat(target.identifiers()).containsExactly(unrelated, head, behind);
    }

    @Test
    @DisplayName("two relays dispatching at once publish one event once")
    void concurrentRelaysDoNotDoublePublish() throws Exception {
        UUID eventId = enqueue(Identifiers.newIdentifier(), "pledge.confirmed");
        Instant attemptAt = now();

        CountDownLatch inTheMiddleOfDispatching = new CountDownLatch(1);
        CountDownLatch releaseTheFirstRelay = new CountDownLatch(1);
        Recording target = new Recording();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // The first relay holds the row's lock — and its transaction — while the
            // second one looks for work. This is the interleaving that a claim written
            // as a read followed by an update cannot survive, and the reason the claim
            // is FOR UPDATE SKIP LOCKED inside one statement.
            Future<Outcome> first = pool.submit(() -> dispatch.dispatchNext(attemptAt, message -> {
                target.dispatch(message);
                inTheMiddleOfDispatching.countDown();
                try {
                    if (!releaseTheFirstRelay.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the second relay never finished");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));

            assertThat(inTheMiddleOfDispatching.await(30, TimeUnit.SECONDS)).isTrue();

            Outcome second = dispatch.dispatchNext(attemptAt, target);
            releaseTheFirstRelay.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.PUBLISHED);
            // Skipped rather than queued behind the lock: a relay that waited would
            // publish the row a second time the moment the first one committed.
            assertThat(second).isEqualTo(Outcome.NOTHING_TO_DO);
        } finally {
            pool.shutdownNow();
        }

        assertThat(target.identifiers()).containsExactly(eventId);
        assertThat(stateOf(eventId)).isEqualTo("PUBLISHED");
    }
}
