package az.ideanest.analytics;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §8.4's {@code analytics-aggregator}: when it runs, which calendar it counts by, and
 * how far back it recomputes.
 *
 * <p>A record of its own rather than a fifth field on {@link AnalyticsProperties},
 * bound under {@code ideanest.analytics.aggregation}. Two reasons, and the second is
 * the one that decided it: the rollup and the referral rule are tuned by different
 * people for different reasons, and a nested block on one record means every change to
 * either is a change to one file. Spring binds a sub-prefix to its own record without
 * either knowing about the other.
 *
 * <p>Defaulted in code, as everything in this module is, for the reason
 * {@link AnalyticsProperties} gives: a deployment that configures none of this has to
 * start, and the value it starts with has to be one somebody argued for rather than a
 * zero left behind by binding. The one exception is {@link #schedule()}, which also
 * appears in {@code application.yml} — §8.4 is a table of sixteen schedules and an
 * operator asking "when does the aggregator run" reads them in one place.
 *
 * @param schedule when the pass fires, as a UTC cron expression, or {@code -} to
 *     register the job without a timer. §8.4 says hourly. <strong>Hourly for a daily
 *     table</strong> is not a contradiction: the day being rolled up is the one in
 *     progress, so an hourly pass is what makes today's number on the dashboard within
 *     an hour of the truth rather than a day behind it
 * @param zone <strong>whose calendar a "day" belongs to.</strong> The correctness
 *     question in this whole feature, and V27's header argues it in full: UTC would put
 *     every pledge between midnight and four in the morning — the tail of the evening,
 *     where the traffic is — on the previous day for a creator in Baku, and the
 *     dashboard would disagree with their own calendar with nothing on screen
 *     explaining why. {@code projects} carries no zone of its own (V6), so a
 *     per-campaign answer would be a column nobody sets.
 *     <p>Read by the writer and by the reader, and frozen onto every row it wrote, so
 *     that changing it is visible rather than retroactive
 * @param reRollupWindow <strong>how far back every pass recomputes.</strong> The answer
 *     to late-arriving data. A pledge is attributed when {@code pledge.confirmed} is
 *     delivered, which the outbox retries with a backoff, so an attribution can land
 *     after its own day has closed. Three days is four orders of magnitude beyond the
 *     relay's ten-minute ceiling and its eight attempts, and it bounds the pass: an
 *     unbounded "recompute everything" would grow with the platform and eventually
 *     overlap its own next tick. Beyond the window a day stops moving until somebody
 *     re-runs the range, which {@code AnalyticsRollupService.rollUp} exists for
 * @param staleAfter <strong>how old the newest rollup row may be before a pass says so
 *     out loud.</strong> A rollup that silently stops is worse than no rollup: the
 *     dashboard keeps rendering, the numbers keep looking plausible, and nothing
 *     anywhere is wrong enough to page. Six hours is comfortably more than an hourly
 *     schedule plus the scheduler's backoff and comfortably less than a working day
 */
@ConfigurationProperties(prefix = "ideanest.analytics.aggregation")
public record AnalyticsAggregationProperties(
        String schedule, ZoneId zone, Duration reRollupWindow, Duration staleAfter) {

    /** §8.4: hourly, on the hour. The same shape the other hourly sweeps use. */
    private static final String DEFAULT_SCHEDULE = "0 0 * * * *";

    /**
     * Azerbaijan, which is where this platform's creators and its currency are.
     *
     * <p>UTC+4 with no daylight saving since 2016, so a day here is twenty-four hours
     * and a midnight is unambiguous. The arithmetic still goes through {@link ZoneId}
     * rather than a fixed offset, so a zone that does observe it would still bucket
     * correctly.
     */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Baku");

    private static final Duration DEFAULT_RE_ROLLUP_WINDOW = Duration.ofDays(3);

    private static final Duration DEFAULT_STALE_AFTER = Duration.ofHours(6);

    /**
     * The furthest back a re-rollup window may reach.
     *
     * <p>Not a tuning knob — a guard. A window of a year configured by a typo would
     * make every hourly pass a full recomputation of the platform's history, which is
     * a job that overlaps its own next tick and is indistinguishable from an outage
     * until somebody looks at a query plan.
     */
    private static final Duration LONGEST_RE_ROLLUP_WINDOW = Duration.ofDays(90);

    public AnalyticsAggregationProperties {
        schedule = schedule == null || schedule.isBlank() ? DEFAULT_SCHEDULE : schedule;
        zone = zone == null ? DEFAULT_ZONE : zone;
        reRollupWindow = reRollupWindow == null ? DEFAULT_RE_ROLLUP_WINDOW : reRollupWindow;
        staleAfter = staleAfter == null ? DEFAULT_STALE_AFTER : staleAfter;

        if (reRollupWindow.isNegative()) {
            // Zero is meaningful and allowed: it recomputes only the day in progress,
            // which is what a deployment that has decided its events are never late
            // would ask for. Negative is not.
            throw new IllegalArgumentException("A re-rollup window does not reach into the future");
        }
        if (reRollupWindow.compareTo(LONGEST_RE_ROLLUP_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "A re-rollup window of " + reRollupWindow + " would make every hourly pass a full rebuild;"
                            + " the bound is " + LONGEST_RE_ROLLUP_WINDOW
                            + " and a longer range is a backfill somebody runs on purpose");
        }
        if (!staleAfter.isPositive()) {
            throw new IllegalArgumentException("A staleness threshold is a positive duration");
        }
    }

    /**
     * The zone as PostgreSQL is asked to read it.
     *
     * <p>The identifier is handed to {@code AT TIME ZONE} as a parameter, so the two
     * sides have to agree about it. They do: PostgreSQL ships the IANA database and
     * {@link ZoneId} is the same vocabulary. A name neither of them knows is refused by
     * the binder at start-up, which is the point of the property being a
     * {@link ZoneId} rather than a {@link String} — a typo is a process that does not
     * start, not a scheduled pass that fails hourly with nobody reading the log.
     */
    public String zoneId() {
        return zone.getId();
    }
}
