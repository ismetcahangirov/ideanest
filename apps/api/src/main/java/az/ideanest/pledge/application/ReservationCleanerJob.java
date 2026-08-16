package az.ideanest.pledge.application;

import az.ideanest.pledge.PledgeProperties;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §8.4's {@code reservation-cleaner}, every minute: gives back the places held by
 * checkouts nobody finished.
 *
 * <p><strong>This is the price of not using a Redis TTL</strong>, and it is the
 * whole of it. PostgreSQL has no expiry, so a reservation lapses because a job
 * notices that it has — see V17 for why the reservation lives in the database
 * anyway. §8.4 lists this job, so the sweep is the design rather than a workaround
 * for it, and it buys something back: an expired reservation is a row with a state
 * and a history rather than a key that silently stopped existing.
 *
 * <p><strong>This belongs on the durable scheduler, not here.</strong> There is no
 * job queue yet (#134), so this is Spring's {@code @Scheduled}: an in-process timer
 * with no record of what it did, no retry, and no visibility. It is adequate for
 * the same reasons {@code AccountAnonymisationJob} and {@code LaunchReminderJob}
 * are — the work is idempotent and bounded — with one difference worth stating: a
 * minute late here is a minute in which a limited tier looks sold out while a place
 * is actually free. That is a lost sale rather than a wrong one, which is why a
 * timer is tolerable for it and would not be for anything that moves money. When
 * #134 lands this becomes a durable job and the annotation goes.
 *
 * <p><strong>On more than one instance</strong> every replica runs this on its own
 * timer, so several will look for lapsed drafts at once. That is safe rather than
 * merely tolerable: {@link ReservationExpiry#release} claims each row with a
 * conditional update, so exactly one caller credits the tier and the others find it
 * already done. The cost of not having a leader election is some duplicated reads,
 * which is the right trade against a lock nobody maintains.
 */
@Component
public class ReservationCleanerJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationCleanerJob.class);

    private final PledgeRepository pledges;
    private final ReservationExpiry expiry;
    private final PledgeProperties properties;
    private final Clock clock;

    public ReservationCleanerJob(
            PledgeRepository pledges, ReservationExpiry expiry, PledgeProperties properties, Clock clock) {
        this.pledges = pledges;
        this.expiry = expiry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The schedule is a property so that the test profile can set it to {@code -}
     * and drive {@link #releaseExpiredReservations(Instant)} directly. A timer
     * firing in the background of a test suite is a source of failures that
     * reproduce once a fortnight — and this one fires every minute, at rows a
     * concurrency test is in the middle of asserting on.
     */
    @Scheduled(cron = "${ideanest.pledge.reservation.cleanup-schedule}", zone = "UTC")
    public void run() {
        releaseExpiredReservations(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * One pass: up to a batch of drafts whose reservation has run out.
     *
     * <p>Takes the instant rather than reading the clock, so that a test can ask
     * what happens after the TTL without waiting for it, and so that every row in
     * one pass is judged against one moment.
     *
     * <p>Bounded rather than exhaustive. A campaign that closed with thousands of
     * checkouts in flight must not be one pass that overlaps its own next tick; the
     * remainder is found a minute later, in expiry order, so the place that has
     * been unavailable longest is the first one given back.
     *
     * @return how many reservations this pass released
     */
    public int releaseExpiredReservations(Instant now) {
        List<UUID> lapsed =
                pledges.findLapsedDrafts(now, PageRequest.ofSize(properties.reservation().cleanupBatchSize()));

        int released = 0;
        for (UUID pledgeId : lapsed) {
            try {
                if (expiry.release(pledgeId, now)) {
                    released++;
                }
            } catch (RuntimeException e) {
                // One reservation per transaction, and one failure must not stop the
                // rest: every draft behind this one is also holding a place that
                // somebody else could be buying. The row stays lapsed and the next
                // pass tries again.
                log.error("Could not release reservation {}; it stays held until the next pass.", pledgeId, e);
            }
        }

        if (released > 0) {
            log.info("Released {} of {} lapsed reservations.", released, lapsed.size());
        }
        return released;
    }
}
