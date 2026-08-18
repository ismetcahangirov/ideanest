package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.analytics.application.AnalyticsRollupService;
import az.ideanest.analytics.application.ProjectAnalytics;
import az.ideanest.analytics.application.ProjectAnalyticsService;
import az.ideanest.analytics.domain.RollupWindow;
import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §8.4's {@code analytics-aggregator} against a real database: what it writes, and what
 * happens when it is run again.
 *
 * <p><strong>Attributions are inserted directly rather than driven through the outbox.</strong>
 * {@code ReferralAttributionTests} already exercises the whole event path and is the
 * right place for it; what this suite is about is the arithmetic over rows that are
 * already there, and the instants those rows carry are the subject rather than a
 * by-product. Writing them by hand is how {@code pledged_at} can be placed on either
 * side of a midnight to the second.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aSecondPassOverTheSameDaysChangesNothing()} — CLAUDE.md makes state
 *       transitions and money arithmetic not optional to test, and re-running a rollup
 *       is where a double count would come from. This is the assertion that #95's
 *       idempotency is a property of the arithmetic rather than a hope.
 *   <li>{@link #aPledgeJustAfterMidnightInBakuIsCountedOnTheNewDay()} — the timezone
 *       decision, asserted through PostgreSQL rather than only through Java. Both sides
 *       have to agree about where a day starts or the rollup and the read disagree by
 *       four hours.
 *   <li>{@link #tenthsAndFifthsAddUpExactly()} — 0.1 + 0.2 is not 0.3 in binary floating
 *       point and this is somebody's pledge.
 *   <li>{@link #aQuietDayHasNoRowAndDoesNotBreakTheRunningTotal()} — the read side has
 *       to survive gaps, because writing a zero row for every campaign for every day is
 *       the worse table.
 *   <li>{@link #anAttributionOlderThanTheWindowNeedsABackfill()} — the bound on
 *       late-arriving data, stated as a test rather than as a promise.
 * </ul>
 */
class AnalyticsRollupTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the campaigns these tests create. A counter rather than a slice of an
     * identifier, for {@code ReferralAttributionTests}' reason.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Baku is UTC+4, so a local midnight is 20:00 the previous day in UTC. */
    private static final String MIDDAY = "T09:00:00Z";

    @Autowired
    private AnalyticsRollupService rollups;

    @Autowired
    private ProjectAnalyticsService analytics;

    @Autowired
    private AnalyticsAggregationProperties properties;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private UUID creatorId;
    private UUID projectId;

    @BeforeEach
    void campaign() {
        jdbc = new JdbcTemplate(dataSource);
        String handle = "rollup-" + SEQUENCE.incrementAndGet();
        creatorId = Campaigns.creator(dataSource, handle);
        projectId = Campaigns.seed(dataSource, creatorId, handle).state("LIVE").insert();
    }

    @AfterEach
    void clear() {
        clock.reset();
        // Attributions and both rollup tables cascade from the campaign.
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    // ------------------------------------------------------------------
    // What a pass writes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a day's row is the count and the sum of the pledges confirmed on it")
    void aDayIsTheCountAndSumOfItsPledges() {
        attribute("2026-03-10" + MIDDAY, "100.00", "SOCIAL", "twitter");
        attribute("2026-03-10" + MIDDAY, "50.00", "EMAIL", "newsletter");

        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));

        ProjectAnalytics.Day rolled = onlyDay(read("2026-03-10", "2026-03-10"));
        assertThat(rolled.day()).isEqualTo(day("2026-03-10"));
        assertThat(rolled.pledgeCount()).isEqualTo(2);
        assertThat(rolled.amount().amount()).isEqualByComparingTo("150.00");
        assertThat(rolled.cumulativePledgeCount()).isEqualTo(2);
        assertThat(rolled.cumulativeAmount().amount()).isEqualByComparingTo("150.00");
        // The zone is frozen onto the row rather than assumed at read time, so a later
        // reconfiguration is visible instead of retroactive.
        assertThat(rolled.timeZone()).isEqualTo(properties.zone().getId());
    }

    @Test
    @DisplayName("the running total covers every earlier day, including ones outside the range read")
    void theRunningTotalCoversEveryEarlierDay() {
        attribute("2026-03-08" + MIDDAY, "100.00", "SOCIAL", "twitter");
        attribute("2026-03-10" + MIDDAY, "25.00", "SOCIAL", "twitter");

        rollups.rollUp(day("2026-03-08"), day("2026-03-10"));

        // Read only the second day. Its cumulative has to include the first, which is the
        // property that makes the rollup answerable one row at a time.
        ProjectAnalytics.Day later = onlyDay(read("2026-03-10", "2026-03-10"));
        assertThat(later.pledgeCount()).isEqualTo(1);
        assertThat(later.amount().amount()).isEqualByComparingTo("25.00");
        assertThat(later.cumulativePledgeCount()).isEqualTo(2);
        assertThat(later.cumulativeAmount().amount()).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("a quiet day has no row and does not break the running total")
    void aQuietDayHasNoRowAndDoesNotBreakTheRunningTotal() {
        attribute("2026-03-08" + MIDDAY, "100.00", "SOCIAL", "twitter");
        attribute("2026-03-10" + MIDDAY, "25.00", "SOCIAL", "twitter");

        rollups.rollUp(day("2026-03-08"), day("2026-03-10"));

        List<ProjectAnalytics.Day> days = read("2026-03-08", "2026-03-10").days();
        // Two rows for three days. A zero row would be indistinguishable from a day the
        // job has not reached, which is the one distinction computed_at exists to keep.
        assertThat(days).hasSize(2);
        assertThat(days.stream().map(ProjectAnalytics.Day::day))
                .containsExactly(day("2026-03-08"), day("2026-03-10"));
    }

    @Test
    @DisplayName("a campaign with nothing attributed reports no total rather than zero")
    void aCampaignWithNothingAttributedReportsNoTotal() {
        rollups.rollUp(day("2026-03-08"), day("2026-03-10"));

        ProjectAnalytics empty = read("2026-03-08", "2026-03-10");
        assertThat(empty.days()).isEmpty();
        // Absent, not zero, following the referral report: "this campaign took nothing"
        // and "this campaign took zero manat" are different sentences.
        assertThat(empty.currency()).isNull();
        assertThat(empty.computedAt()).isNull();
    }

    @Test
    @DisplayName("a day is split by the channel its pledges came from")
    void aDayIsSplitByChannel() {
        attribute("2026-03-10" + MIDDAY, "100.00", "SOCIAL", "twitter");
        attribute("2026-03-10" + MIDDAY, "40.00", "SOCIAL", "instagram");
        attribute("2026-03-10" + MIDDAY, "60.00", "EMAIL", "newsletter");

        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));

        List<ProjectAnalytics.ChannelTotal> channels = onlyDay(read("2026-03-10", "2026-03-10")).channels();
        assertThat(channels).hasSize(2);
        // Most valuable first, which is the order §4.7 asks for by calling them "top
        // sources"; the two Twitter and Instagram visits are one SOCIAL row, because the
        // finer labels stay in GET /referrers where they are folded at read time.
        assertThat(channels.get(0).channel().name()).isEqualTo("SOCIAL");
        assertThat(channels.get(0).pledgeCount()).isEqualTo(2);
        assertThat(channels.get(0).amount().amount()).isEqualByComparingTo("140.00");
        assertThat(channels.get(1).channel().name()).isEqualTo("EMAIL");
        assertThat(channels.get(1).amount().amount()).isEqualByComparingTo("60.00");
    }

    // ------------------------------------------------------------------
    // Running it again
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a second pass over the same days changes nothing")
    void aSecondPassOverTheSameDaysChangesNothing() {
        attribute("2026-03-10" + MIDDAY, "100.00", "SOCIAL", "twitter");
        attribute("2026-03-10" + MIDDAY, "50.00", "EMAIL", "newsletter");

        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));
        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));
        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));

        // One row, not three, and the numbers are the day's rather than three times it.
        // The whole of #95's idempotency is that a day is a pure function of the rows in
        // it — nothing accumulates onto what was there before.
        assertThat(dailyRowCount()).isEqualTo(1);
        assertThat(channelRowCount()).isEqualTo(2);
        ProjectAnalytics.Day rolled = onlyDay(read("2026-03-10", "2026-03-10"));
        assertThat(rolled.pledgeCount()).isEqualTo(2);
        assertThat(rolled.amount().amount()).isEqualByComparingTo("150.00");
        assertThat(rolled.cumulativeAmount().amount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("a pledge attributed after its day was rolled up is picked up inside the window")
    void aLateAttributionInsideTheWindowIsPickedUp() {
        // Yesterday, in the platform's calendar, so that the scheduled pass covers it.
        clock.freeze();
        LocalDate yesterday = today().minusDays(1);
        attribute(noonOn(yesterday), "100.00", "SOCIAL", "twitter");

        rollups.rollUpRecentDays();
        assertThat(onlyDay(read(yesterday, yesterday)).pledgeCount()).isEqualTo(1);

        // The relay retried, or the pledge module published late. pledged_at is when the
        // pledge was confirmed and not when the row arrived, so the attribution belongs
        // to a day that has already been rolled up.
        attribute(noonOn(yesterday), "25.00", "EMAIL", "newsletter");
        rollups.rollUpRecentDays();

        ProjectAnalytics.Day rolled = onlyDay(read(yesterday, yesterday));
        assertThat(rolled.pledgeCount()).isEqualTo(2);
        assertThat(rolled.amount().amount()).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("an attribution older than the re-rollup window needs a backfill, and gets one")
    void anAttributionOlderThanTheWindowNeedsABackfill() {
        clock.freeze();
        // Comfortably outside the window, whatever it is configured to.
        LocalDate old = today().minusDays(properties.reRollupWindow().toDays() + 5);
        attribute(noonOn(old), "100.00", "SOCIAL", "twitter");

        rollups.rollUpRecentDays();
        // Nothing, and that is the bound working rather than failing: the scheduled pass
        // deliberately does not reach back for ever. V27 says so out loud.
        assertThat(read(old, old).days()).isEmpty();

        rollups.rollUp(old, old);

        assertThat(onlyDay(read(old, old)).pledgeCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Where a day starts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pledge just after midnight in Baku is counted on the new day")
    void aPledgeJustAfterMidnightInBakuIsCountedOnTheNewDay() {
        // 00:30 on the 11th in Baku, 20:30 on the 10th in UTC. This is the four-hour
        // window a UTC rollup would file against the previous day — the tail of the
        // evening, where a campaign's traffic actually is.
        attribute("2026-03-10T20:30:00Z", "100.00", "SOCIAL", "twitter");
        // 23:30 on the 10th in Baku: the other side of the same midnight.
        attribute("2026-03-10T19:30:00Z", "40.00", "SOCIAL", "twitter");

        rollups.rollUp(day("2026-03-10"), day("2026-03-11"));

        List<ProjectAnalytics.Day> days = read("2026-03-10", "2026-03-11").days();
        assertThat(days).hasSize(2);
        // Asserted through the database, not only through RollupWindow: the bucketing is
        // PostgreSQL's `AT TIME ZONE` and the boundaries are Java's, and the two agreeing
        // is the thing that could silently stop being true.
        assertThat(days.get(0).day()).isEqualTo(day("2026-03-10"));
        assertThat(days.get(0).amount().amount()).isEqualByComparingTo("40.00");
        assertThat(days.get(1).day()).isEqualTo(day("2026-03-11"));
        assertThat(days.get(1).amount().amount()).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------
    // Money
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenths and fifths add up exactly, because none of this is a double")
    void tenthsAndFifthsAddUpExactly() {
        // 0.1 + 0.2 is 0.30000000000000004 in binary floating point. On a funding
        // platform that is somebody's pledge, and a rollup is where it would be summed.
        attribute("2026-03-10" + MIDDAY, "0.10", "SOCIAL", "twitter");
        attribute("2026-03-10" + MIDDAY, "0.20", "SOCIAL", "twitter");

        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));

        BigDecimal total = onlyDay(read("2026-03-10", "2026-03-10")).amount().amount();
        assertThat(total).isEqualByComparingTo("0.30");
        // At the column's scale, exactly, so two reads of one row cannot differ by it.
        assertThat(total.toPlainString()).isEqualTo("0.30");
    }

    @Test
    @DisplayName("a large day survives the sum without losing a qapik")
    void aLargeDaySurvivesTheSum() {
        // Ten pledges whose total has more significant digits than a double carries
        // exactly. numeric(14,2) holds it; a float would round it and nothing would say
        // so.
        for (int pledge = 0; pledge < 10; pledge++) {
            attribute("2026-03-10" + MIDDAY, "9999999.99", "SOCIAL", "twitter");
        }

        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));

        assertThat(onlyDay(read("2026-03-10", "2026-03-10")).amount().amount().toPlainString())
                .isEqualTo("99999999.90");
    }

    // ------------------------------------------------------------------
    // What is refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a campaign holding two currencies is left out rather than added together")
    void aCampaignHoldingTwoCurrenciesIsLeftOut() {
        attributeIn("2026-03-10" + MIDDAY, "100.00", "AZN");
        attributeIn("2026-03-10" + MIDDAY, "100.00", "USD");

        rollups.rollUp(day("2026-03-10"), day("2026-03-10"));

        // §7.3 says this cannot happen. If it does, a campaign missing from the dashboard
        // with an error line naming it is recoverable, and a total that is the addition
        // of two different kinds of thing is not.
        assertThat(dailyRowCount()).isZero();
        assertThat(read("2026-03-10", "2026-03-10").days()).isEmpty();
    }

    @Test
    @DisplayName("a day that loses its pledges to a change of calendar loses its row too")
    void aDayBucketedUnderAnotherCalendarIsDiscarded() {
        attribute("2026-03-10T20:30:00Z", "100.00", "SOCIAL", "twitter");

        // Rolled up as though the platform had been configured for UTC: the pledge lands
        // on the 10th. Written straight in, because the property is what the service
        // reads and a test that changed it would need a second application context.
        rollups.rollUp(RollupWindow.of(ZoneOffset.UTC, day("2026-03-10"), day("2026-03-11")));
        assertThat(dailyRowCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT day FROM project_analytics_daily WHERE project_id = ?",
                        LocalDate.class,
                        projectId))
                .isEqualTo(day("2026-03-10"));

        // Now under the platform's own calendar, where the same pledge is on the 11th.
        // The 10th has nothing left, so the statement that recomputes it produces no row
        // for it — and an upsert alone would leave the stale one standing.
        rollups.rollUp(day("2026-03-10"), day("2026-03-11"));

        assertThat(dailyRowCount()).isEqualTo(1);
        assertThat(onlyDay(read("2026-03-10", "2026-03-11")).day()).isEqualTo(day("2026-03-11"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** One attributed pledge, written where the rollup will find it. */
    private void attribute(String pledgedAt, String amount, String channel, String source) {
        insertAttribution(pledgedAt, amount, "AZN", channel, source);
    }

    private void attributeIn(String pledgedAt, String amount, String currency) {
        insertAttribution(pledgedAt, amount, currency, "SOCIAL", "twitter");
    }

    private void insertAttribution(
            String pledgedAt, String amount, String currency, String channel, String source) {

        OffsetDateTime confirmed = Instant.parse(pledgedAt).atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                INSERT INTO referral_attributions (
                    id, pledge_id, project_id, touch_id, channel, source, campaign, referrer_code,
                    amount, currency, pledged_at, attributed_at, event_id)
                VALUES (?, ?, ?, NULL, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)
                """,
                Identifiers.newIdentifier(),
                UUID.randomUUID(),
                projectId,
                channel,
                source,
                new BigDecimal(amount),
                currency,
                confirmed,
                confirmed,
                UUID.randomUUID());
    }

    /** Midday in the platform's calendar, which is safely inside whichever day it names. */
    private String noonOn(LocalDate date) {
        return date.atTime(12, 0).atZone(properties.zone()).toInstant().toString();
    }

    private LocalDate today() {
        return RollupWindow.dayOf(clock.instant(), properties.zone());
    }

    private ProjectAnalytics read(String from, String to) {
        return read(day(from), day(to));
    }

    private ProjectAnalytics read(LocalDate from, LocalDate to) {
        return analytics.of(projectId, creatorId, from, to);
    }

    private static ProjectAnalytics.Day onlyDay(ProjectAnalytics analytics) {
        assertThat(analytics.days()).hasSize(1);
        return analytics.days().get(0);
    }

    private static LocalDate day(String date) {
        return LocalDate.parse(date);
    }

    private long dailyRowCount() {
        return count("SELECT count(*) FROM project_analytics_daily WHERE project_id = ?");
    }

    private long channelRowCount() {
        return count("SELECT count(*) FROM project_analytics_daily_channels WHERE project_id = ?");
    }

    private long count(String sql) {
        Long rows = jdbc.queryForObject(sql, Long.class, projectId);
        return rows == null ? 0 : rows;
    }
}
