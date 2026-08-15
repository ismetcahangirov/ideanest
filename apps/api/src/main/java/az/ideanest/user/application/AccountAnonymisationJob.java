package az.ideanest.user.application;

import az.ideanest.user.UserProperties;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds the accounts whose grace period has elapsed and hands them to the
 * anonymiser.
 *
 * <p><strong>This belongs on the durable scheduler, not here.</strong> There is
 * no job queue yet (#134), so this is Spring's {@code @Scheduled}: an in-process
 * timer with no record of what it did, no retry, and no visibility. It is
 * adequate because the work is idempotent, bounded, and not urgent to the
 * minute — an account anonymised an hour late is still anonymised — and it is
 * not adequate for anything with money in it. When #134 lands this becomes a
 * durable job and the annotation goes.
 *
 * <p><strong>On more than one instance</strong> every replica runs this on its
 * own timer, so several will look for due accounts at once. That is safe rather
 * than merely tolerable: {@link AccountAnonymiser#anonymise} locks the row
 * before it decides, so exactly one caller does the work and the others find it
 * already done. The cost of not having a leader election is some duplicated
 * reads, which is the right trade against a lock nobody maintains.
 */
@Component
public class AccountAnonymisationJob {

    private static final Logger log = LoggerFactory.getLogger(AccountAnonymisationJob.class);

    private final UserRepository users;
    private final AccountAnonymiser anonymiser;
    private final UserProperties properties;
    private final Clock clock;

    public AccountAnonymisationJob(
            UserRepository users, AccountAnonymiser anonymiser, UserProperties properties, Clock clock) {
        this.users = users;
        this.anonymiser = anonymiser;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The schedule is a property so that the test profile can set it to
     * {@code -} and drive {@link #anonymiseDueAccounts(Instant)} directly. A
     * timer firing in the background of a test suite is a source of failures
     * that reproduce once a fortnight.
     */
    @Scheduled(cron = "${ideanest.user.anonymisation-schedule}", zone = "UTC")
    public void run() {
        anonymiseDueAccounts(clock.instant());
    }

    /**
     * Works through one batch of due accounts.
     *
     * <p>Takes the instant rather than reading the clock, so that a test can ask
     * what happens after thirty days without waiting thirty days and without
     * replacing a bean the rest of the suite shares.
     *
     * @return how many accounts this run anonymised
     */
    public int anonymiseDueAccounts(Instant now) {
        List<UUID> due = users.findDueForAnonymisation(now, PageRequest.ofSize(properties.anonymisationBatchSize()));

        int anonymised = 0;
        for (UUID userId : due) {
            try {
                if (anonymiser.anonymise(userId, now)) {
                    anonymised++;
                }
            } catch (RuntimeException e) {
                // One account per transaction, and one failure must not stop the
                // rest: a backlog that stalls on a single bad row is a promise
                // to every other user in the batch that we quietly stopped
                // keeping. The row stays due and the next run tries again.
                log.error("Could not anonymise account {}; the request stays pending.", userId, e);
            }
        }

        if (anonymised > 0) {
            log.info("Anonymised {} of {} accounts due.", anonymised, due.size());
        }
        return anonymised;
    }
}
