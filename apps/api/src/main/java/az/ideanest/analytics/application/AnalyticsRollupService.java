package az.ideanest.analytics.application;

import az.ideanest.analytics.AnalyticsAggregationProperties;
import az.ideanest.analytics.domain.RollupWindow;
import az.ideanest.analytics.infrastructure.DailyRollupRepository;
import az.ideanest.shared.observability.LogFields;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §8.4's {@code analytics-aggregator}: one pass of the daily rollup.
 *
 * <h2>What a pass is</h2>
 *
 * <p>Recompute today and the previous {@code ideanest.analytics.aggregation.re-rollup-window}
 * days, for every campaign that took a pledge in that range, in the platform's calendar.
 * Two statements — the totals and the channel split — inside one transaction, because
 * the split has a foreign key to the totals and a dashboard must never be able to read
 * one without the other.
 *
 * <h2>Why re-running is safe, stated as a property rather than a hope</h2>
 *
 * <p>A day's row is a pure function of the attributions inside it. Nothing is
 * incremented, nothing is carried forward from the row that was there before, and the
 * running totals are recomputed from the campaign's first pledge every time. So
 * {@code rollUp} for a range is idempotent by construction rather than by an
 * "already done" flag somebody has to remember to check, and
 * {@code AnalyticsRollupTests.aSecondPassOverTheSameDayChangesNothing} is the assertion
 * that keeps it that way.
 *
 * <p>That is what makes {@link #rollUp(LocalDate, LocalDate)} usable as a backfill: an
 * operator repairing a range, and the scheduled pass repairing the last three days, are
 * the same code path with different arguments.
 *
 * <h2>Late-arriving data</h2>
 *
 * <p>A pledge is attributed when {@code pledge.confirmed} is delivered, and
 * {@code pledged_at} is when the pledge was <em>confirmed</em> — so an attribution can
 * land after its own day has already been rolled up. The re-rollup window is the bounded
 * answer: three days by default, against a relay whose backoff ceiling is ten minutes
 * and whose attempt limit is eight. Anything later than that does not move its day until
 * somebody runs the range by hand. That is a limit rather than a guarantee, and it is
 * written down here, in V27, and in the pull request rather than left to be discovered.
 *
 * <h2>Being visible when it stops</h2>
 *
 * <p>A rollup that silently stops is worse than no rollup at all: the dashboard carries
 * on rendering, the numbers stay plausible, and nothing is broken enough to notice. So a
 * pass that writes nothing while attributions are arriving says so at WARN, the
 * freshness of the newest row is checked against
 * {@code ideanest.analytics.aggregation.stale-after} on every pass, and
 * {@code computed_at} is returned to the dashboard so the staleness is legible at the
 * surface too. Failure itself is the scheduler's business — throwing is what records the
 * attempt in {@code scheduled_jobs}, backs the job off, and eventually dead-letters it.
 */
@Service
public class AnalyticsRollupService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRollupService.class);

    private final DailyRollupRepository rollups;
    private final AnalyticsAggregationProperties properties;
    private final Clock clock;

    public AnalyticsRollupService(
            DailyRollupRepository rollups, AnalyticsAggregationProperties properties, Clock clock) {

        this.rollups = rollups;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The scheduled pass: the day in progress and the re-rollup window behind it.
     *
     * <p>Annotated rather than relying on {@link #rollUp(RollupWindow)} to open the
     * transaction, because it would not: a call from one method of a bean to another
     * goes to {@code this} and never through the proxy, so the annotation on the callee
     * would do nothing. Every public entry point carries it; the inner one then joins
     * the transaction that is already open rather than starting a second.
     *
     * @return what was written, for the job's log line and for a test to assert on
     */
    @Transactional
    public RollupOutcome rollUpRecentDays() {
        Instant now = now();
        warnIfStale(now);

        long days = properties.reRollupWindow().toDays();
        return rollUp(RollupWindow.endingOn(now, properties.zone(), days));
    }

    /**
     * A range, recomputed on purpose: the backfill handle.
     *
     * <p>Both ends inclusive, in the platform's calendar. Re-running a range that has
     * already been rolled up produces the same rows — see the class comment for why that
     * is a property of the arithmetic and not a check.
     */
    @Transactional
    public RollupOutcome rollUp(LocalDate from, LocalDate to) {
        return rollUp(RollupWindow.of(properties.zone(), from, to));
    }

    /**
     * One window, in one transaction.
     *
     * <p>Transactional because the channel split references the totals it is a split of:
     * a crash between the two statements would otherwise leave a day whose parts are
     * missing, which a foreign key can refuse but only inside a transaction that can
     * roll back.
     */
    @Transactional
    public RollupOutcome rollUp(RollupWindow window) {
        Instant computedAt = now();

        List<UUID> mixed = rollups.campaignsWithMixedCurrency(window);
        for (UUID projectId : mixed) {
            // Left out of the rollup entirely rather than reported in one of its two
            // currencies. §7.3 says this cannot happen; if it does, a campaign missing
            // from the dashboard with a line saying which is recoverable, and a total
            // that is the addition of two different kinds of thing is not.
            log.error(
                    "Campaign is not rolled up: its attributions are in more than one currency. {}",
                    LogFields.create().id("projectId", projectId));
        }

        int days = rollups.rollUpDays(window, computedAt);
        int channels = rollups.rollUpChannels(window);

        RollupOutcome outcome = new RollupOutcome(window, days, channels, mixed.size(), computedAt);
        report(outcome);
        return outcome;
    }

    /**
     * What one pass did.
     *
     * @param days how many campaign-days were written or rewritten
     * @param channelRows how many channel splits went with them
     * @param skippedCampaigns campaigns left out because their money is in more than one
     *     currency. Never anything but zero on a platform §7.3 describes, and counted so
     *     that "never" is observable rather than assumed
     */
    public record RollupOutcome(
            RollupWindow window, int days, int channelRows, int skippedCampaigns, Instant computedAt) {
    }

    // ------------------------------------------------------------------
    // Instrumentation
    // ------------------------------------------------------------------

    /**
     * One line per pass, and a louder one when a pass that should have written something
     * did not.
     *
     * <p>The distinction is the whole point. A pass writing zero rows at four in the
     * morning is the platform being quiet; a pass writing zero rows while attributions
     * are landing inside the window is the job having stopped working without having
     * failed, which is the failure mode that survives every other kind of monitoring.
     */
    private void report(RollupOutcome outcome) {
        LogFields fields = LogFields.create()
                .count("days", outcome.days())
                .count("channelRows", outcome.channelRows())
                .count("skippedCampaigns", outcome.skippedCampaigns());

        if (outcome.days() > 0) {
            log.info("Rolled up analytics for {}. {}", outcome.window(), fields);
            return;
        }

        boolean somethingToAggregate = rollups.newestAttribution()
                .map(newest -> !newest.isBefore(outcome.window().startInclusive()))
                .orElse(false);

        if (somethingToAggregate) {
            log.warn(
                    "The analytics rollup wrote nothing for {} while pledges were being attributed inside it. {}",
                    outcome.window(),
                    fields);
        } else {
            log.debug("Nothing to roll up for {}.", outcome.window());
        }
    }

    /**
     * Says so when the newest row in the whole table is older than a rollup should ever
     * be.
     *
     * <p>Checked at the start of a pass rather than at the end, so that the first pass
     * after an outage reports the outage rather than reporting the recovery. An empty
     * table is not stale: it is a platform where nothing has been attributed yet, which
     * is exactly where this one is until {@code pledge.confirmed} is published.
     */
    private void warnIfStale(Instant now) {
        Optional<Instant> newest = rollups.newestRollup();
        if (newest.isEmpty()) {
            return;
        }

        Duration age = Duration.between(newest.get(), now);
        if (age.compareTo(properties.staleAfter()) > 0) {
            log.warn(
                    "The newest analytics rollup is {} old, past the {} this platform expects. {}",
                    age,
                    properties.staleAfter(),
                    LogFields.create().at("newestRollup", newest.get()));
        }
    }

    /** The clock, at the resolution PostgreSQL stores instants at. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
