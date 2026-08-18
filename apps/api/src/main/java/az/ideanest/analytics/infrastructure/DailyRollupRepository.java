package az.ideanest.analytics.infrastructure;

import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.analytics.domain.RollupWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * §8.4's {@code analytics-aggregator} as SQL, and the dashboard's read beside it.
 *
 * <h2>Why this is raw SQL and not JPA</h2>
 *
 * <p>Because the work is set arithmetic. The alternative — load every attribution a
 * campaign ever took as managed entities, group them in Java, and write a row per day —
 * turns a query PostgreSQL answers with one scan into as many object graphs as the
 * campaign has backers, and it gets worse exactly as the campaign gets more successful.
 * That is the same argument {@code ReferralAttributionRepository#report} makes for
 * aggregating in the database, and it is stronger here: the rollup exists to make a
 * read cheap, so a rollup that is itself expensive has spent the saving in advance.
 * §11 names jOOQ for the queries where the query is the point; this module has no jOOQ
 * yet and one statement does not justify introducing it, so it is
 * {@code NamedParameterJdbcTemplate}, which is what {@code discovery.infrastructure}
 * already uses for the same reason.
 *
 * <h2>Why the day boundaries arrive as instants</h2>
 *
 * <p>Every statement here is bounded by {@code pledged_at >= ? AND pledged_at < ?}
 * rather than by {@code (pledged_at AT TIME ZONE …)::date BETWEEN ? AND ?}, even though
 * the second reads better. An expression over a column is not a range over the column:
 * the planner cannot use {@code referral_attributions_pledged_at_idx} for it and falls
 * back to reading the table. {@link RollupWindow} converts the dates once, and the
 * difference is a range scan against a sequential one.
 *
 * <h2>Idempotency</h2>
 *
 * <p>A day's numbers are a pure function of the attributions inside it, so the write is
 * an upsert on {@code (project_id, day)} and running the same window twice produces the
 * same rows. Nothing is accumulated onto what was already there — the running totals are
 * recomputed from the campaign's first day every time — which is what makes a re-run
 * safe rather than merely tolerated. V27's header argues why that is worth the range
 * scan.
 */
@Repository
public class DailyRollupRepository {

    /**
     * Campaigns whose attributions landed inside the window, and the subset of those
     * whose money is all in one currency.
     *
     * <p>Shared by the two write statements as a prefix rather than written twice,
     * because the second copy is where the two would drift: a channel split computed
     * over a wider set of campaigns than the totals it is a split of is a dashboard
     * whose parts do not add up to its whole.
     *
     * <p>The currency clause is §7.3's invariant checked rather than assumed. Every
     * pledge on a campaign is in the campaign's currency and §21.2 forbids a conversion
     * rate as the basis of a collection, so this excludes nothing in practice — and if
     * it ever does, the campaign is left out and {@link #campaignsWithMixedCurrency}
     * says which, rather than a row being written whose amount is the addition of two
     * different kinds of thing.
     */
    private static final String SOUND_CAMPAIGNS =
            """
            WITH touched AS (
                SELECT DISTINCT a.project_id
                  FROM referral_attributions a
                 WHERE a.pledged_at >= :startInclusive
                   AND a.pledged_at <  :endExclusive
            ),
            one_currency AS (
                SELECT a.project_id
                  FROM referral_attributions a
                  JOIN touched t ON t.project_id = a.project_id
                 GROUP BY a.project_id
                HAVING count(DISTINCT a.currency) = 1
            )
            """;

    /**
     * The rollup itself: every day of every campaign the window touched, with the
     * campaign's running totals attached.
     *
     * <p><strong>{@code daily} is deliberately not bounded by the window.</strong> The
     * cumulative columns are the campaign's totals from its first pledge, so the
     * running sum has to see every day; only the {@code INSERT} is narrowed to the
     * range being rewritten. That is the cost V27's header takes on purpose, and the
     * covering index {@code referral_attributions_rollup_idx} is what keeps it a
     * per-campaign range scan rather than a heap fetch per pledge.
     *
     * <p>{@code min(currency)} is safe because {@code one_currency} has already
     * established there is only one — an aggregate is needed only because
     * {@code currency} is not in the {@code GROUP BY}, and putting it there would let a
     * mixed campaign produce two rows for one day and lose one of them to the conflict
     * target.
     */
    private static final String ROLL_UP_DAYS = SOUND_CAMPAIGNS
            + """
            , daily AS (
                SELECT a.project_id                                                AS project_id,
                       (a.pledged_at AT TIME ZONE CAST(:zone AS text))::date       AS day,
                       min(a.currency)                                             AS currency,
                       count(*)                                                    AS pledge_count,
                       sum(a.amount)                                               AS amount
                  FROM referral_attributions a
                  JOIN one_currency c ON c.project_id = a.project_id
                 GROUP BY a.project_id, (a.pledged_at AT TIME ZONE CAST(:zone AS text))::date
            ),
            running AS (
                SELECT d.project_id,
                       d.day,
                       d.currency,
                       d.pledge_count,
                       d.amount,
                       sum(d.pledge_count) OVER campaign AS cumulative_pledge_count,
                       sum(d.amount)       OVER campaign AS cumulative_amount
                  FROM daily d
                WINDOW campaign AS (
                    PARTITION BY d.project_id ORDER BY d.day
                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                )
            )
            INSERT INTO project_analytics_daily (
                project_id, day, time_zone, currency, pledge_count, amount,
                cumulative_pledge_count, cumulative_amount, computed_at)
            SELECT r.project_id,
                   r.day,
                   CAST(:zone AS text),
                   r.currency,
                   r.pledge_count,
                   r.amount,
                   r.cumulative_pledge_count,
                   r.cumulative_amount,
                   :computedAt
              FROM running r
             WHERE r.day >= CAST(:firstDay AS date)
               AND r.day <= CAST(:lastDay AS date)
            ON CONFLICT (project_id, day) DO UPDATE
               SET time_zone               = EXCLUDED.time_zone,
                   currency                = EXCLUDED.currency,
                   pledge_count            = EXCLUDED.pledge_count,
                   amount                  = EXCLUDED.amount,
                   cumulative_pledge_count = EXCLUDED.cumulative_pledge_count,
                   cumulative_amount       = EXCLUDED.cumulative_amount,
                   computed_at             = EXCLUDED.computed_at
            """;

    /**
     * The same days, split by channel.
     *
     * <p>Bounded by the window rather than by the campaign's whole history, unlike the
     * statement above: there is no running total here, so there is nothing outside the
     * range to read.
     *
     * <p>Runs after the parent statement and inside the same transaction, because
     * {@code project_analytics_daily_channels_day_fkey} refuses a split whose day has
     * no total.
     */
    private static final String ROLL_UP_CHANNELS = SOUND_CAMPAIGNS
            + """
            , daily AS (
                SELECT a.project_id                                          AS project_id,
                       (a.pledged_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                       a.channel                                             AS channel,
                       count(*)                                              AS pledge_count,
                       sum(a.amount)                                         AS amount
                  FROM referral_attributions a
                  JOIN one_currency c ON c.project_id = a.project_id
                 WHERE a.pledged_at >= :startInclusive
                   AND a.pledged_at <  :endExclusive
                 GROUP BY a.project_id,
                          (a.pledged_at AT TIME ZONE CAST(:zone AS text))::date,
                          a.channel
            )
            INSERT INTO project_analytics_daily_channels (project_id, day, channel, pledge_count, amount)
            SELECT d.project_id, d.day, d.channel, d.pledge_count, d.amount
              FROM daily d
            ON CONFLICT (project_id, day, channel) DO UPDATE
               SET pledge_count = EXCLUDED.pledge_count,
                   amount       = EXCLUDED.amount
            """;

    /**
     * Rows in the window that were bucketed under a different calendar.
     *
     * <p>The one case an upsert alone cannot repair. If the platform zone is
     * reconfigured, a pledge can move from one day to the next — and the day it left may
     * end up with no pledges at all, which means the statement above produces no row for
     * it and the stale one survives. Deleting first is the whole fix, it is scoped to
     * the window being rewritten, and on every ordinary pass it deletes nothing.
     *
     * <p>Rows <em>outside</em> the window keep the zone they were computed under and
     * say so in {@code time_zone}, which is exactly what V27 freezes that column for: a
     * reconfiguration is visible at the read side rather than quietly rewriting the
     * part of history the job happened to reach.
     */
    private static final String DISCARD_DAYS_FROM_ANOTHER_CALENDAR =
            """
            DELETE FROM project_analytics_daily d
             WHERE d.day >= CAST(:firstDay AS date)
               AND d.day <= CAST(:lastDay AS date)
               AND d.time_zone <> CAST(:zone AS text)
               AND d.project_id IN (
                   SELECT DISTINCT a.project_id
                     FROM referral_attributions a
                    WHERE a.pledged_at >= :startInclusive
                      AND a.pledged_at <  :endExclusive
               )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DailyRollupRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Recomputes every campaign-day the window covers.
     *
     * @return how many daily rows were written or rewritten. Zero is an ordinary answer
     *     — no campaign took a pledge in the window — and is not distinguishable from a
     *     failure here, which is why {@code AnalyticsRollupService} asks
     *     {@link #newestAttribution()} rather than reading anything into it
     */
    public int rollUpDays(RollupWindow window, Instant computedAt) {
        MapSqlParameterSource parameters = parametersFor(window).addValue("computedAt", at(computedAt));
        jdbc.update(DISCARD_DAYS_FROM_ANOTHER_CALENDAR, parameters);
        return jdbc.update(ROLL_UP_DAYS, parameters);
    }

    /** The channel split for the same window. Must follow {@link #rollUpDays}. */
    public int rollUpChannels(RollupWindow window) {
        return jdbc.update(ROLL_UP_CHANNELS, parametersFor(window));
    }

    /**
     * Campaigns the window touched whose attributions are not all in one currency.
     *
     * <p>Empty on every platform §7.3 describes. Asked anyway, because the alternative
     * to reporting it is a campaign silently missing from the dashboard with no line
     * anywhere saying which or why.
     */
    public List<UUID> campaignsWithMixedCurrency(RollupWindow window) {
        return jdbc.queryForList(
                """
                SELECT a.project_id
                  FROM referral_attributions a
                 WHERE a.project_id IN (
                       SELECT DISTINCT b.project_id
                         FROM referral_attributions b
                        WHERE b.pledged_at >= :startInclusive
                          AND b.pledged_at <  :endExclusive
                   )
                 GROUP BY a.project_id
                HAVING count(DISTINCT a.currency) > 1
                """,
                parametersFor(window),
                UUID.class);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * §4.7's trend for one campaign: the stored rows, in order.
     *
     * <p><strong>This is the query the whole issue exists for.</strong>
     * {@code project_analytics_daily_pkey} is {@code (project_id, day)}, so it is an
     * index range scan over exactly the days asked for — bounded by the length of the
     * range and not by how many pledges the campaign took. {@code AnalyticsAccessPathTests}
     * asserts the plan rather than trusting this comment.
     */
    public List<DailyAnalyticsRow> daysOf(UUID projectId, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                SELECT d.day, d.time_zone, d.currency, d.pledge_count, d.amount,
                       d.cumulative_pledge_count, d.cumulative_amount, d.computed_at
                  FROM project_analytics_daily d
                 WHERE d.project_id = :projectId
                   AND d.day >= CAST(:from AS date)
                   AND d.day <= CAST(:to AS date)
                 ORDER BY d.day
                """,
                range(projectId, from, to),
                (rs, row) -> new DailyAnalyticsRow(
                        rs.getObject("day", LocalDate.class),
                        rs.getString("time_zone"),
                        rs.getString("currency"),
                        rs.getLong("pledge_count"),
                        rs.getBigDecimal("amount"),
                        rs.getLong("cumulative_pledge_count"),
                        rs.getBigDecimal("cumulative_amount"),
                        rs.getObject("computed_at", OffsetDateTime.class).toInstant()));
    }

    /** The channel split for the same range, in the same one-index-scan shape. */
    public List<DailyChannelRow> channelsOf(UUID projectId, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                SELECT c.day, c.channel, c.pledge_count, c.amount
                  FROM project_analytics_daily_channels c
                 WHERE c.project_id = :projectId
                   AND c.day >= CAST(:from AS date)
                   AND c.day <= CAST(:to AS date)
                 ORDER BY c.day, c.channel
                """,
                range(projectId, from, to),
                (rs, row) -> new DailyChannelRow(
                        rs.getObject("day", LocalDate.class),
                        // Parsed rather than carried as text: the column's check
                        // constraint and this enum are the same closed set, and a value
                        // outside it should fail here and not in a dashboard.
                        ReferralChannel.valueOf(rs.getString("channel")),
                        rs.getLong("pledge_count"),
                        rs.getBigDecimal("amount")));
    }

    // ------------------------------------------------------------------
    // Whether the job is still alive
    // ------------------------------------------------------------------

    /**
     * When any campaign-day was last computed, across the whole table.
     *
     * <p>The signal that a rollup has stopped. A dashboard that keeps rendering
     * yesterday's numbers looks exactly like a quiet week, so the absence of a symptom
     * is the symptom — see {@code AnalyticsRollupService} for what is done with this.
     */
    public Optional<Instant> newestRollup() {
        return Optional.ofNullable(jdbc.queryForObject(
                        "SELECT max(computed_at) FROM project_analytics_daily",
                        new MapSqlParameterSource(),
                        OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }

    /**
     * The most recent pledge there is anything to aggregate about.
     *
     * <p>Read only to tell two zeroes apart: a pass that wrote nothing because nothing
     * happened, and a pass that wrote nothing while attributions were arriving. The
     * first is the ordinary state of a platform at four in the morning; the second is a
     * fault.
     */
    public Optional<Instant> newestAttribution() {
        return Optional.ofNullable(jdbc.queryForObject(
                        "SELECT max(pledged_at) FROM referral_attributions",
                        new MapSqlParameterSource(),
                        OffsetDateTime.class))
                .map(OffsetDateTime::toInstant);
    }

    // ------------------------------------------------------------------

    private static MapSqlParameterSource parametersFor(RollupWindow window) {
        return new MapSqlParameterSource()
                .addValue("zone", window.zone().getId())
                .addValue("firstDay", window.firstDay())
                .addValue("lastDay", window.lastDay())
                .addValue("startInclusive", at(window.startInclusive()))
                .addValue("endExclusive", at(window.endExclusive()));
    }

    private static MapSqlParameterSource range(UUID projectId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("from", from)
                .addValue("to", to);
    }

    /**
     * An instant as the driver takes one.
     *
     * <p>{@link OffsetDateTime} at UTC rather than {@link Instant}, which the PostgreSQL
     * driver has no mapping for: the same conversion every other raw-SQL caller in this
     * service makes, and the offset is irrelevant to the value because a
     * {@code timestamptz} stores a point in time.
     */
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
