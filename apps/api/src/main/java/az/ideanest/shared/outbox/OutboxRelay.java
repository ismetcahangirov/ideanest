package az.ideanest.shared.outbox;

import az.ideanest.shared.jobs.ScheduledJob;
import az.ideanest.shared.outbox.OutboxDispatch.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The other half of the outbox: something that drains it.
 *
 * <p>{@link Outbox} makes the event and the business write atomic, and that guarantee is
 * worth nothing until a process publishes what was written. This is that process — a poll
 * that asks {@link OutboxDispatch} for one event at a time until the queue is empty or the
 * pass is spent.
 *
 * <p><strong>A poll, and not a notification.</strong> {@code LISTEN}/{@code NOTIFY} would
 * be lower latency and it would also be a second mechanism to get right: a notification
 * delivered while no relay was connected is simply lost, so the poll would have to exist
 * anyway as the thing that actually guarantees delivery — which is the same argument §8.4
 * makes about {@code reminder-sender}, where the event is latency and the sweep is the
 * guarantee. One mechanism that is always correct beats two where the fast one is
 * load-bearing only when it happens to work.
 *
 * <p><strong>On more than one instance</strong> every replica polls on its own timer, and
 * that is safe rather than merely tolerable: the claim is
 * {@code FOR UPDATE SKIP LOCKED}, so two relays divide the queue between them and never
 * meet on a row. Ordering within an aggregate survives it, because a row being dispatched
 * by one relay is still {@code PENDING} and therefore blocks its own successors from the
 * other.
 *
 * <p><strong>The trigger is #134's, and nothing else here changed.</strong> This is a
 * {@link ScheduledJob} rather than a {@code @Scheduled} method, so the tick claims a
 * lease before it polls and is counted when it throws. The relay did not need that to be
 * correct — the claim above is what makes it correct, and it still is — but it did need
 * it to stop being wasteful: every replica used to open a transaction every second to
 * find out that another replica had already emptied the queue.
 *
 * <p>One consequence is worth stating rather than discovering: Spring's scheduler is a
 * single thread by default and every job in the service shares it, so a transport that
 * takes seconds to refuse delays the anonymiser and the reservation sweep behind it.
 * That is unchanged by #134 — the lease decides who runs, not how many threads run —
 * and it is the first thing that stops being tolerable when the transport is no longer
 * in process.
 */
@Component
public class OutboxRelay implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxDispatch dispatch;
    private final OutboxDispatcher target;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(
            OutboxDispatch dispatch, OutboxDispatcher target, OutboxProperties properties, Clock clock) {
        this.dispatch = dispatch;
        this.target = target;
        this.properties = properties;
        this.clock = clock;
    }

    /** §8.4's {@code outbox-relay}. */
    @Override
    public String name() {
        return "outbox-relay";
    }

    /**
     * The schedule is a property so that the test profile can set it to {@code -} and
     * drive {@link #publishPending(Instant)} directly, for the reason
     * {@code ReservationCleanerJob} gives about its own sweep: a timer firing in the
     * background of a test suite acts on the very rows a test is about to assert on — and
     * this one fires every second.
     */
    @Override
    public String schedule() {
        return properties.pollSchedule();
    }

    @Override
    public void run() {
        publishPending(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** One pass over the queue, to the platform's own transport. */
    public int publishPending(Instant now) {
        return publishPending(now, target);
    }

    /**
     * One pass over the queue.
     *
     * <p>Bounded rather than exhaustive. A backlog built up while a transport was down
     * must not be one pass that overlaps its own next tick, and the remainder is a second
     * away.
     *
     * <p>A failure does not end the pass. Every event still waiting belongs to somebody
     * else's aggregate, and stopping on the first refusal would let one stuck consumer
     * hold up the platform's traffic — which is the same reason {@code ReservationCleanerJob}
     * carries on past a reservation it could not release.
     *
     * @param now the instant this pass judges eligibility and backoff against, so that
     *     every row in it is judged against one moment rather than against a clock that
     *     moved while the pass was running
     * @param target where the events go. A parameter for the reason {@link OutboxDispatch}
     *     gives: it lets a test supply a transport that refuses without replacing a bean
     *     and splitting the context cache
     * @return how many events this pass published
     */
    public int publishPending(Instant now, OutboxDispatcher target) {
        int published = 0;
        int failed = 0;

        for (int attempted = 0; attempted < properties.batchSize(); attempted++) {
            Outcome outcome = dispatch.dispatchNext(now, target);
            if (outcome == Outcome.NOTHING_TO_DO) {
                break;
            }
            if (outcome == Outcome.PUBLISHED) {
                published++;
            } else {
                failed++;
            }
        }

        if (failed > 0) {
            log.warn("Outbox pass published {} events and could not publish {}.", published, failed);
        } else if (published > 0) {
            log.debug("Outbox pass published {} events.", published);
        }
        return published;
    }
}
