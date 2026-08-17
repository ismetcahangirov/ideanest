package az.ideanest.shared.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often the relay looks for work, how hard it tries, and when it gives up.
 *
 * @param pollSchedule every second, because latency here is user-visible: §12.1's
 *     pledge counter and the notifications behind it both wait on this. A property
 *     rather than a constant so that the test profile can set it to {@code -} —
 *     Spring's own value for "do not schedule this" — for the reason
 *     {@code ReservationCleanerJob} gives: a poll firing in the background of a test
 *     suite publishes the very rows a test is about to assert on
 * @param batchSize a bound on one pass, not a target. A backlog built up during a
 *     transport outage must not be one pass that overlaps its own next tick; the
 *     relay comes back a second later for the rest
 * @param maxAttempts <strong>the number of deliveries an event gets before it is a
 *     dead letter.</strong> Bounded on purpose: an event that has failed the same way
 *     eight times is not waiting for the network, it is waiting for a person, and
 *     retrying it for ever costs the transport a request per poll and buries the
 *     failure in a log nobody reads. The count includes the first attempt, so this is
 *     eight deliveries and not one plus eight
 * @param retryBackoff the delay after the first failure, doubled per attempt. Not
 *     zero: an immediate retry against a transport that has just refused is the same
 *     request arriving again before anything can have changed
 * @param maxBackoff the ceiling the doubling stops at. Without it the eighth delay
 *     would be measured in hours and the event would have stopped moving in every
 *     practical sense while still calling itself retryable
 */
@ConfigurationProperties(prefix = "ideanest.outbox")
public record OutboxProperties(
        String pollSchedule, int batchSize, int maxAttempts, Duration retryBackoff, Duration maxBackoff) {

    /** Every second. The relay is on the path of things a person is watching happen. */
    private static final String DEFAULT_SCHEDULE = "* * * * * *";

    private static final int DEFAULT_BATCH_SIZE = 100;

    private static final int DEFAULT_MAX_ATTEMPTS = 8;

    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);

    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(10);

    /**
     * Beyond this the doubling has certainly passed any sane ceiling, and shifting
     * further would overflow. The cap is applied before the arithmetic rather than
     * after it.
     */
    private static final int LARGEST_USEFUL_EXPONENT = 30;

    public OutboxProperties {
        // Binding leaves an omitted property at its zero value, so a deployment that
        // configures none of this would otherwise get a relay that never polls, never
        // dispatches, and dead-letters every event on its first failure.
        pollSchedule = pollSchedule == null || pollSchedule.isBlank() ? DEFAULT_SCHEDULE : pollSchedule;
        batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxAttempts = maxAttempts == 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;
        maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;

        if (batchSize < 1) {
            throw new IllegalArgumentException("A relay pass dispatches at least one event");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("An event is attempted at least once");
        }
        if (!retryBackoff.isPositive()) {
            throw new IllegalArgumentException("A retry waits for a positive duration");
        }
        if (maxBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("The backoff ceiling is not below the first delay");
        }
    }

    /**
     * How long to wait after {@code attempt} deliveries have failed.
     *
     * <p>Exponential and capped: the first delay, doubled once per failure, until
     * {@link #maxBackoff()}. Exponential because the failures worth retrying are
     * transient and the ones that are not should be given progressively less of the
     * transport's attention; capped because doubling has no natural end and an event
     * whose next attempt is in nine hours has stopped moving.
     *
     * @param attempt which delivery has just failed, counted from one — the same
     *     number as the row's {@code attempts} after the failure was recorded
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
}
