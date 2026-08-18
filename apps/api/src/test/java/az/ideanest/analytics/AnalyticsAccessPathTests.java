package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How PostgreSQL reaches the rollup — the property #95 exists for, asserted rather than
 * described.
 *
 * <p><strong>Why a plan is worth a test at all.</strong> Every other assertion about this
 * feature would still pass if the dashboard read the table with a sequential scan: the
 * numbers would be right and the endpoint would be slow, and it would get slower exactly
 * as a campaign got more successful. That is the failure this issue was opened to
 * prevent, and it is invisible to a correctness test. So the shape of the read is the
 * subject here, and the assertion is on {@code EXPLAIN}.
 *
 * <p><strong>Why the tables are filled first.</strong> A planner given four rows chooses
 * a sequential scan and is right to, so a plan assertion against a fixture the size of
 * the other suites' would either fail or have to be forced with {@code enable_seqscan},
 * which asserts nothing. These tests write a few thousand rows across several campaigns
 * and {@code ANALYZE} them, so the plan that comes back is the one a real table produces.
 *
 * <p><strong>The writer is deliberately not covered.</strong> Its window predicate is
 * answered by {@code referral_attributions_pledged_at_idx}, and
 * {@code referral_attributions} is empty in every environment until
 * {@code pledge.confirmed} is published — there is nothing to plan against that would
 * mean anything. V27 records the intent; this covers the read, which is the half that has
 * rows and the half a creator waits on.
 */
class AnalyticsAccessPathTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Enough campaigns that one campaign's rows are a small part of the table. */
    private static final int CAMPAIGNS = 8;

    /** Enough days each that a week out of them is selective. */
    private static final int DAYS = 300;

    /**
     * The most rows a read of one week may plan to touch.
     *
     * <p>A week is seven rows and a campaign's history here is {@link #DAYS}. Anything
     * between the two is the read having stopped being bounded by the range, which is
     * the regression this class exists to catch; the margin is generous because the
     * planner's estimate is an estimate, and narrow because the two numbers it has to
     * tell apart are forty times apart.
     */
    private static final long WORTH_READING = DAYS / 4L;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private UUID projectId;

    @BeforeEach
    void rollupWorthPlanningAgainst() {
        jdbc = new JdbcTemplate(dataSource);
        UUID creatorId = Campaigns.creator(dataSource, "analytics-path");

        for (int campaign = 0; campaign < CAMPAIGNS; campaign++) {
            String slug = "analytics-path-" + SEQUENCE.incrementAndGet();
            UUID id = Campaigns.seed(dataSource, creatorId, slug).state("LIVE").insert();
            fill(id);
            // Any one of them; they are identical, and the plan is about the shape of the
            // read rather than about which campaign is asking.
            projectId = id;
        }

        // Without this the planner is working from no statistics at all, which is a
        // different question from the one being asked.
        jdbc.execute("ANALYZE project_analytics_daily");
        jdbc.execute("ANALYZE project_analytics_daily_channels");
    }

    @AfterEach
    void clear() {
        // Both rollup tables cascade from the campaign.
        jdbc.update("DELETE FROM projects WHERE slug LIKE 'analytics-path-%'");
    }

    @Test
    @DisplayName("a campaign's days are read by the primary key, over the range asked for")
    void aCampaignsDaysAreReadByThePrimaryKey() {
        String plan = planOf(
                """
                SELECT d.day, d.time_zone, d.currency, d.pledge_count, d.amount,
                       d.cumulative_pledge_count, d.cumulative_amount, d.computed_at
                  FROM project_analytics_daily d
                 WHERE d.project_id = ?
                   AND d.day >= CAST(? AS date)
                   AND d.day <= CAST(? AS date)
                 ORDER BY d.day
                """);

        assertThat(plan).contains("project_analytics_daily_pkey");
        assertThat(plan).doesNotContain("Seq Scan");
        // Both halves of the key are in the index condition, so the range narrows the
        // scan rather than being applied to rows the scan already read.
        assertThat(plan).containsPattern("Index Cond:.*project_id =");
        assertThat(plan).containsPattern("Index Cond:.*day >=");
        assertThat(rowsPlannedFor(plan)).isLessThan(WORTH_READING);
    }

    @Test
    @DisplayName("the channel split is read the same way")
    void theChannelSplitIsReadTheSameWay() {
        String plan = planOf(
                """
                SELECT c.day, c.channel, c.pledge_count, c.amount
                  FROM project_analytics_daily_channels c
                 WHERE c.project_id = ?
                   AND c.day >= CAST(? AS date)
                   AND c.day <= CAST(? AS date)
                 ORDER BY c.day, c.channel
                """);

        assertThat(plan).contains("project_analytics_daily_channels_pkey");
        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(plan).containsPattern("Index Cond:.*project_id =");
        assertThat(plan).containsPattern("Index Cond:.*day >=");
        assertThat(rowsPlannedFor(plan)).isLessThan(WORTH_READING);
    }

    // ------------------------------------------------------------------

    /** One campaign's whole history, written set-wise rather than a row at a time. */
    private void fill(UUID id) {
        jdbc.update(
                """
                INSERT INTO project_analytics_daily (
                    project_id, day, time_zone, currency, pledge_count, amount,
                    cumulative_pledge_count, cumulative_amount, computed_at)
                SELECT ?, DATE '2024-01-01' + d, 'Asia/Baku', 'AZN', 1, 10.00,
                       d + 1, (d + 1) * 10.00, now()
                  FROM generate_series(0, CAST(? AS int)) AS d
                """,
                id,
                DAYS - 1);
        jdbc.update(
                """
                INSERT INTO project_analytics_daily_channels (project_id, day, channel, pledge_count, amount)
                SELECT ?, DATE '2024-01-01' + d, 'SOCIAL', 1, 10.00
                  FROM generate_series(0, CAST(? AS int)) AS d
                """,
                id,
                DAYS - 1);
    }

    /** The plan for one week of one campaign, as text. */
    private String planOf(String query) {
        List<String> lines =
                jdbc.queryForList("EXPLAIN " + query, String.class, projectId, "2024-06-01", "2024-06-07");
        return String.join("\n", lines);
    }

    /**
     * The largest row estimate anywhere in the plan.
     *
     * <p>This, rather than the name of the node, is the assertion that means something.
     * Whether PostgreSQL reaches the index by an {@code Index Scan} or by a
     * {@code Bitmap Index Scan}, and whether it sorts the handful of rows afterwards, is
     * a cost decision that moves with the size of the table and the machine — it is
     * genuinely the planner's business. What must never move is how much of the table
     * the read touches: a plan bounded by the length of the range asked for reads seven
     * rows, and one bounded by the campaign's history reads {@link #DAYS}. Pinning the
     * node type would make this test fail on a table that grew, which is the opposite of
     * what it is for.
     */
    private static long rowsPlannedFor(String plan) {
        Matcher rows = Pattern.compile("rows=(\\d+)").matcher(plan);
        long largest = 0;
        while (rows.find()) {
            largest = Math.max(largest, Long.parseLong(rows.group(1)));
        }
        assertThat(largest).as("the plan states a row estimate somewhere").isPositive();
        return largest;
    }
}
