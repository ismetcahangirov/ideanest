package az.ideanest.analytics.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of {@code project_analytics_daily}, as the database holds it.
 *
 * <p>An amount and a currency rather than a {@code Money}, for
 * {@code MoneyAmountConverter}'s reason: §7.2 stores the two as separate columns and a
 * {@code Money} is assembled at the edge — here, by
 * {@code application.ProjectAnalyticsService}, which is also the layer that would
 * refuse a mixture. Never a {@code double} on either side of the boundary.
 *
 * @param day the calendar day, in {@link #timeZone()}
 * @param timeZone the zone whose midnights bounded it, read back rather than assumed:
 *     a row written under an earlier configuration says so, and V27's header says why
 *     that matters
 * @param pledgeCount how many confirmed pledges landed that day. Also the day's backer
 *     count — {@code pledges_project_backer_active_key} allows one active pledge per
 *     backer per campaign — and named for what is counted rather than for what it
 *     implies
 * @param cumulativeAmount the campaign's total up to and including this day, so that a
 *     day with no row cannot make a running line drop
 * @param computedAt when the rollup last wrote this row. The freshness the dashboard
 *     needs in order to tell a quiet week from an aggregator that stopped
 */
public record DailyAnalyticsRow(
        LocalDate day,
        String timeZone,
        String currency,
        long pledgeCount,
        BigDecimal amount,
        long cumulativePledgeCount,
        BigDecimal cumulativeAmount,
        Instant computedAt) {
}
