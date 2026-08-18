package az.ideanest.shared.idempotency;

import az.ideanest.shared.jobs.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §17.2's twenty-four hours: removes the keys nobody can still be retrying.
 *
 * <p><strong>The retention is a guarantee with an end</strong>, and this is the end.
 * Without it {@code idempotency_keys} grows by one row per payment mutation for
 * ever, and every one of those rows is a record of something somebody bought — kept
 * long after the retry it existed to catch became impossible. §17.2 names the
 * period; a period nothing enforces is a comment.
 *
 * <p><strong>On the durable scheduler since #134</strong>, and this is the job that
 * needed it least. Its only effect is a delete, so two replicas sweeping at once
 * already meant one of them removed the row and the other found nothing to remove —
 * there was no second effect to keep in step and therefore nothing to claim. What the
 * lease buys is that one replica does the deleting instead of all of them, and what
 * the scheduler buys is that a sweep failing against a database under pressure backs
 * off instead of returning every hour to fail identically.
 *
 * <p>Late is cheap here. A missed hour is an hour of rows that outlive their
 * purpose, which is a retention overrun rather than a wrong answer — the keys are
 * still matched by expiry when they are read, never by whether the sweep has been
 * past.
 */
@Component
public class IdempotencyKeySweeper implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeySweeper.class);

    private final IdempotencyRecords records;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyKeySweeper(IdempotencyRecords records, IdempotencyProperties properties, Clock clock) {
        this.records = records;
        this.properties = properties;
        this.clock = clock;
    }

    /** §8.4's {@code idempotency-key-cleaner}. */
    @Override
    public String name() {
        return "idempotency-key-cleaner";
    }

    /**
     * The schedule is a property so that the test profile can set it to {@code -}
     * and drive {@link #removeExpiredKeys(Instant)} directly, for the reason
     * {@code ReservationCleanerJob} gives: a timer firing in the background of a
     * test suite deletes the very rows a test is about to assert on.
     */
    @Override
    public String schedule() {
        return properties.sweepSchedule();
    }

    @Override
    public void run() {
        removeExpiredKeys(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * One bounded pass, oldest first.
     *
     * <p>Takes the instant rather than reading the clock, so that a test can ask
     * what happens a day later without waiting a day, and so that every row in one
     * pass is judged against one moment.
     *
     * @return how many keys this pass removed
     */
    public int removeExpiredKeys(Instant now) {
        int removed = records.removeExpired(now, properties.sweepBatchSize());
        if (removed > 0) {
            log.info("Removed {} idempotency keys past their {} retention.", removed, properties.retention());
        }
        return removed;
    }
}
