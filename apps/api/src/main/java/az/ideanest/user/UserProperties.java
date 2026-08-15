package az.ideanest.user;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Account lifecycle settings.
 *
 * @param deletionGracePeriod how long a closed account can still be recovered.
 *     §17.4 sets it at thirty days. It is configurable because it is a policy
 *     decision that may have to move with a legal answer, and not configurable
 *     per request because a user who is told a date must get that date.
 * @param anonymisationBatchSize how many accounts one run of the job works
 *     through. A bound, not a target: without one, a backlog built up during an
 *     outage would be attempted in a single transaction-per-row loop that runs
 *     for as long as it takes and overlaps its own next tick.
 * @param anonymisationSchedule cron expression for the job. {@code -} disables
 *     it, which is what the test profile uses so that the suite drives the
 *     anonymiser directly instead of waiting for a tick.
 * @param rateLimit how often an account may ask for its data or for its own
 *     deletion.
 */
@ConfigurationProperties(prefix = "ideanest.user")
public record UserProperties(
        Duration deletionGracePeriod,
        int anonymisationBatchSize,
        String anonymisationSchedule,
        RateLimit rateLimit) {

    /**
     * @param exportsPerAccount data exports one account may request per window.
     *     An export endpoint is a data exfiltration endpoint if it is cheap to
     *     call: one stolen access token should yield one copy of the account,
     *     not a tap on it
     * @param deletionAttemptsPerAccount deletion requests per account per
     *     window. The endpoint verifies a password, which makes it a password
     *     oracle that an attacker holding an access token could otherwise use
     *     without limit
     * @param window the period both are measured over
     */
    public record RateLimit(int exportsPerAccount, int deletionAttemptsPerAccount, Duration window) {
    }
}
