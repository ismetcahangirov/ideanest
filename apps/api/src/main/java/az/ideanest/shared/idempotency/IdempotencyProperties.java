package az.ideanest.shared.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a spent key is remembered, and how the forgotten ones are removed.
 *
 * @param retention <strong>Twenty-four hours, and it is §17.2's number</strong> —
 *     "keys retained 24 hours". Configuration rather than a constant for the reason
 *     {@code PledgeProperties} gives about the reservation TTL: it is a judgement
 *     with a cost on each side. Shorter, and a client retrying after a long outage
 *     creates a second pledge; longer, and a table of what everybody bought
 *     yesterday keeps growing for a guarantee nobody is still relying on. It is
 *     also what lets a test ask what happens a day later without waiting a day
 * @param sweepSchedule hourly, on the hour, like {@code account-anonymiser}. The
 *     retention is a day, so the minute it runs at cannot matter; what matters is
 *     that a missed hour costs an hour of rows. A property so that the test profile
 *     can set it to {@code -} — Spring's own value for "do not schedule this" — and
 *     drive the sweep directly, because a timer firing in the background of a test
 *     suite deletes the very rows a test is about to assert on
 * @param sweepBatchSize a bound on one pass, not a target. A day of traffic must
 *     not be one transaction; the sweep returns an hour later for the rest
 */
@ConfigurationProperties(prefix = "ideanest.idempotency")
public record IdempotencyProperties(Duration retention, String sweepSchedule, int sweepBatchSize) {

    /** §17.2: "Idempotency — required on all payment mutations; keys retained 24 hours". */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private static final String DEFAULT_SCHEDULE = "0 0 * * * *";

    private static final int DEFAULT_BATCH_SIZE = 500;

    public IdempotencyProperties {
        // Binding leaves an omitted property at its zero value, so a deployment
        // that configures none of this gets §17.2's rule rather than a retention of
        // zero — which would expire every key the instant it was written and turn
        // every retry into a second charge.
        retention = retention == null ? DEFAULT_RETENTION : retention;
        sweepSchedule = sweepSchedule == null || sweepSchedule.isBlank() ? DEFAULT_SCHEDULE : sweepSchedule;
        sweepBatchSize = sweepBatchSize == 0 ? DEFAULT_BATCH_SIZE : sweepBatchSize;

        if (!retention.isPositive()) {
            throw new IllegalArgumentException("Idempotency keys are retained for a positive duration");
        }
        if (sweepBatchSize < 1) {
            throw new IllegalArgumentException("A sweep removes at least one key");
        }
    }
}
