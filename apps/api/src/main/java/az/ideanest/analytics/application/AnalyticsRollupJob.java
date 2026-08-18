package az.ideanest.analytics.application;

import az.ideanest.analytics.AnalyticsAggregationProperties;
import az.ideanest.shared.jobs.ScheduledJob;
import org.springframework.stereotype.Component;

/**
 * §8.4's {@code analytics-aggregator}, hourly: "populate daily rollups".
 *
 * <p>The trigger and nothing else. The work is {@link AnalyticsRollupService}, which an
 * operator and a test can also call directly — a job that is the only way to run its own
 * work is a job that cannot be backfilled without a deployment.
 *
 * <p><strong>On the shared scheduler, because this one genuinely needs the lease.</strong>
 * Most of §8.4's jobs claim their own rows and were safe on a per-replica timer before
 * #134; this one is not that shape. Three replicas running the same window at the same
 * moment would each recompute the same days and race to upsert them — which is
 * *survivable*, because the arithmetic is idempotent and the last writer writes the same
 * numbers, and is still three times the work for one answer, three times the lock traffic
 * on {@code project_analytics_daily}, and a deadlock waiting for the day two passes touch
 * the same rows in a different order. The lease makes it one pass.
 *
 * <p><strong>Hourly for a daily table is deliberate.</strong> The day being aggregated is
 * the one in progress, so an hourly pass is what puts today's figure on the dashboard
 * within an hour of the truth instead of a day behind it. It is also what makes the
 * re-rollup window cheap: three days recomputed twenty-four times is three days of
 * attributions read, not the platform's history.
 *
 * <p>Throwing is how a failed pass is recorded. {@code JobRunner} counts the attempt in
 * {@code scheduled_jobs}, releases the lease, and backs off; catching a failure here to
 * keep the log tidy would have the job recorded as having succeeded, which is worse than
 * either the failure or the noise.
 */
@Component
public class AnalyticsRollupJob implements ScheduledJob {

    private final AnalyticsRollupService rollups;
    private final AnalyticsAggregationProperties properties;

    public AnalyticsRollupJob(AnalyticsRollupService rollups, AnalyticsAggregationProperties properties) {
        this.rollups = rollups;
        this.properties = properties;
    }

    /** §8.4's name for it, verbatim. It is the key the lease is taken on. */
    @Override
    public String name() {
        return "analytics-aggregator";
    }

    /**
     * A property rather than a literal, exactly as every other job here reads it: the
     * test profile sets it to {@code -} and drives
     * {@link AnalyticsRollupService#rollUp} directly, because a timer firing in the
     * background of a suite would recompute the very rows a test is about to assert on.
     */
    @Override
    public String schedule() {
        return properties.schedule();
    }

    @Override
    public void run() {
        rollups.rollUpRecentDays();
    }
}
