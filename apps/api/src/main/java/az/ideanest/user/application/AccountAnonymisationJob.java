package az.ideanest.user.application;

import az.ideanest.shared.jobs.ScheduledJob;
import az.ideanest.user.UserProperties;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Finds the accounts whose grace period has elapsed and hands them to the
 * anonymiser.
 *
 * <p><strong>On the durable scheduler since #134.</strong> This job did not need
 * the lease to be correct — {@link AccountAnonymiser#anonymise} locks the row
 * before it decides, so exactly one caller ever did the work and the others found
 * it already done — and it is the job that gains the most from being counted.
 * §17.4's grace period expiring is the platform keeping a promise to somebody who
 * asked to be forgotten, and an hourly sweep that had been throwing since Tuesday
 * previously said so only in a log line nobody was looking at.
 *
 * <p>An account anonymised an hour late is still anonymised, which is why this
 * was safe on an unclaimed timer in the first place.
 */
@Component
public class AccountAnonymisationJob implements ScheduledJob {

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

    /** §8.4's {@code account-anonymiser}. */
    @Override
    public String name() {
        return "account-anonymiser";
    }

    /**
     * The schedule is a property so that the test profile can set it to
     * {@code -} and drive {@link #anonymiseDueAccounts(Instant)} directly. A
     * timer firing in the background of a test suite is a source of failures
     * that reproduce once a fortnight.
     */
    @Override
    public String schedule() {
        return properties.anonymisationSchedule();
    }

    @Override
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
