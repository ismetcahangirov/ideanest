package az.ideanest.shared.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p><strong>An in-process {@code @Scheduled} timer, on §8.4's terms.</strong> The
 * durable scheduler is #134, so this is the same arrangement
 * {@code ReservationCleanerJob} and {@code AccountAnonymisationJob} already have,
 * and it is safe on more than one replica for a simpler reason than either of them:
 * the sweep's only effect is a delete, so two replicas running it at once means one
 * of them removes the row and the other finds nothing to remove. There is no second
 * effect to keep in step and therefore nothing to claim.
 *
 * <p>Late is cheap here. A missed hour is an hour of rows that outlive their
 * purpose, which is a retention overrun rather than a wrong answer — the keys are
 * still matched by expiry when they are read, never by whether the sweep has been
 * past.
 */
@Component
public class IdempotencyKeySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeySweeper.class);

    private final IdempotencyRecords records;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyKeySweeper(IdempotencyRecords records, IdempotencyProperties properties, Clock clock) {
        this.records = records;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The schedule is a property so that the test profile can set it to {@code -}
     * and drive {@link #removeExpiredKeys(Instant)} directly, for the reason
     * {@code ReservationCleanerJob} gives: a timer firing in the background of a
     * test suite deletes the very rows a test is about to assert on.
     */
    @Scheduled(cron = "${ideanest.idempotency.sweep-schedule}", zone = "UTC")
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
