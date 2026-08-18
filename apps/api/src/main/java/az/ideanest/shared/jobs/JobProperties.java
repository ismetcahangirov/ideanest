package az.ideanest.shared.jobs;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a replica may hold a job, how hard a failing one is tried, and when it is
 * given up on.
 *
 * @param holder <strong>what this process calls itself in a lease.</strong> A
 *     diagnostic and not an authority: nothing is decided by comparing it to anything
 *     except the row's own previous value, so a deployment that leaves two replicas
 *     with the same name loses the ability to tell which one is running a job and
 *     loses nothing else. From the environment because a container knows its pod name
 *     and the JVM does not; the fallback is the JVM's {@code pid@host}, which is
 *     enough to find the process in a log
 * @param lockLease <strong>how long the platform believes a holder that has stopped
 *     answering.</strong> The one number with a cost on both sides. Too long, and a
 *     replica killed mid-run takes its job down with it for that long — every
 *     reservation unreleased, every notice unsent, and no symptom but an absence.
 *     Too short, and a run that legitimately takes longer than this is joined by a
 *     second replica part-way through. A minute is comfortably longer than any pass
 *     here, all of which are bounded batches, and short enough that a crash costs a
 *     minute of one job
 * @param maxAttempts <strong>consecutive failures before the job is a dead
 *     letter.</strong> Bounded on purpose, and it is {@code OutboxProperties}'
 *     argument exactly: a job that has failed the same way eight times is waiting for
 *     a person, not for the network. The count is of failures in a row, so a job that
 *     fails once an hour and succeeds the rest of the time never reaches it
 * @param retryBackoff the delay after the first failure, doubled per consecutive
 *     failure. Not zero: an immediate retry against a database or a transport that
 *     has just refused is the same request arriving again before anything can have
 *     changed
 * @param maxBackoff the ceiling the doubling stops at. Without it the eighth delay
 *     would be measured in hours, and the job would have stopped running in every
 *     practical sense while still calling itself retryable
 */
@ConfigurationProperties(prefix = "ideanest.jobs")
public record JobProperties(
        String holder, Duration lockLease, int maxAttempts, Duration retryBackoff, Duration maxBackoff) {

    /**
     * Longer than any pass §8.4 currently describes, all of which are bounded
     * batches, and short enough that a replica dying mid-run costs one minute of one
     * job rather than an outage somebody has to notice.
     */
    private static final Duration DEFAULT_LOCK_LEASE = Duration.ofMinutes(1);

    /** The relay's number, for the relay's reason. */
    private static final int DEFAULT_MAX_ATTEMPTS = 8;

    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);

    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(10);

    /**
     * Beyond this the doubling has certainly passed any sane ceiling, and shifting
     * further would overflow. The cap is applied before the arithmetic rather than
     * after it.
     */
    private static final int LARGEST_USEFUL_EXPONENT = 30;

    public JobProperties {
        // Binding leaves an omitted property at its zero value, so a deployment that
        // configures none of this would otherwise take a lease of zero — every job
        // claimable by every replica on every tick, which is the failure this package
        // exists to prevent, arrived at by not mentioning it.
        holder = holder == null || holder.isBlank() ? thisProcess() : holder;
        lockLease = lockLease == null ? DEFAULT_LOCK_LEASE : lockLease;
        maxAttempts = maxAttempts == 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;
        maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;

        if (!lockLease.isPositive()) {
            throw new IllegalArgumentException("A lease is held for a positive duration");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("A job is attempted at least once");
        }
        if (!retryBackoff.isPositive()) {
            throw new IllegalArgumentException("A retry waits for a positive duration");
        }
        if (maxBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("The backoff ceiling is not below the first delay");
        }
    }

    /**
     * How long to wait after {@code attempt} consecutive failures.
     *
     * <p>Exponential and capped, and deliberately the same rule as
     * {@code OutboxProperties#backoffAfter}: two schedules that back off differently
     * are two policies to hold in mind during one incident. It is written twice
     * rather than shared because the two records configure two features that are
     * tuned separately, and extracting the arithmetic with one caller on each side
     * would couple the relay's tuning to the scheduler's. The third caller is where
     * it moves into a helper.
     *
     * @param attempt which consecutive failure has just happened, counted from one —
     *     the same number as the row's {@code attempts} after it was recorded
     */
    public Duration backoffAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempts are counted from one; there is no delay before the first");
        }
        if (attempt > LARGEST_USEFUL_EXPONENT) {
            return maxBackoff;
        }
        Duration backoff = retryBackoff.multipliedBy(1L << (attempt - 1));
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    /**
     * {@code pid@host}, which is what the JVM knows about where it is running.
     *
     * <p>Not guaranteed to be either by the specification, which is why it is only
     * ever read by a person: the lease is enforced by the row, never by trusting what
     * a process wrote into this column.
     */
    private static String thisProcess() {
        return ManagementFactory.getRuntimeMXBean().getName();
    }
}
