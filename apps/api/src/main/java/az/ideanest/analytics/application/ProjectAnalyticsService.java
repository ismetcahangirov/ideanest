package az.ideanest.analytics.application;

import az.ideanest.analytics.AnalyticsAggregationProperties;
import az.ideanest.analytics.domain.RollupWindow;
import az.ideanest.analytics.infrastructure.DailyAnalyticsRow;
import az.ideanest.analytics.infrastructure.DailyChannelRow;
import az.ideanest.analytics.infrastructure.DailyRollupRepository;
import az.ideanest.project.application.ProjectAccess;
import az.ideanest.shared.money.CurrencyMismatchException;
import az.ideanest.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /v1/projects/{id}/analytics}: the rollup, read by the creator.
 *
 * <h2>Who may read it</h2>
 *
 * <p>{@link ProjectAccess#requireEditableLocks} — the creator, or a collaborator holding
 * any editing capability. <strong>The same check the referral report makes today, and
 * deliberately not a different one.</strong> The right authority for a campaign's money
 * is arguably {@code VIEW_FINANCES}, and this module cannot name it:
 * {@code Capability} lives in {@code project.domain} and {@code ModuleBoundaryTests}
 * forbids reaching for it from here, which is the wall {@code ReferralReportService}
 * documents at length. Narrowing both call sites together is one change inside the
 * project module and one line on each side of it; making this endpoint stricter than the
 * one next to it in the meantime would only mean two answers to one question.
 *
 * <p>A stranger gets {@code ProjectNotFoundException} and a 404, for the reason the
 * referral report gives: what a campaign is raising and when is competitive information,
 * and a 403 would confirm the campaign exists to somebody enumerating identifiers.
 *
 * <h2>Why this is cheap, which is the entire point of #95</h2>
 *
 * <p>Two index range scans and no aggregation. {@code project_analytics_daily_pkey} is
 * {@code (project_id, day)} and the channel table's is {@code (project_id, day, channel)},
 * so both reads are bounded by the length of the range asked for rather than by how many
 * pledges the campaign has taken — which is the difference between a dashboard that gets
 * slower as a campaign succeeds and one that does not.
 * {@code AnalyticsAccessPathTests} asserts the plan rather than trusting the sentence.
 *
 * <h2>The calendar</h2>
 *
 * <p>{@code from} and {@code to} are calendar days in
 * {@code ideanest.analytics.aggregation.zone}, the same property the rollup buckets by.
 * A read side that resolved "today" differently from the writer would be the bug this
 * feature is most likely to have, so there is one property and both sides read it.
 */
@Service
public class ProjectAnalyticsService {

    /**
     * How much of the trend a caller that asked for none gets.
     *
     * <p>Thirty days, which is a campaign's median life on this platform (§5.3 allows one
     * to sixty) and is what CD-02's chart renders. A default of "everything" would make
     * the cheapest endpoint in the dashboard the one whose cost grows with the campaign,
     * which is the property this whole issue exists to remove.
     */
    private static final int DEFAULT_DAYS = 30;

    /**
     * The longest range this endpoint answers.
     *
     * <p>A guard rather than a preference. §5.3 bounds a campaign's funding window at
     * sixty days and the pledge manager adds a tail measured in months, so a year is
     * comfortably more than any real question and small enough that the response cannot
     * become a megabyte because somebody sent {@code from=1970-01-01}.
     */
    private static final int MAX_DAYS = 366;

    private final ProjectAccess projects;
    private final DailyRollupRepository rollups;
    private final AnalyticsAggregationProperties properties;
    private final Clock clock;

    public ProjectAnalyticsService(
            ProjectAccess projects,
            DailyRollupRepository rollups,
            AnalyticsAggregationProperties properties,
            Clock clock) {

        this.projects = projects;
        this.rollups = rollups;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One campaign's daily trend.
     *
     * @param from the first day, inclusive, or null for {@link #DEFAULT_DAYS} before
     *     {@code to}
     * @param to the last day, inclusive, or null for today in the platform's calendar
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign
     *     that does not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException for a
     *     collaborator who holds a grant but no editing capability
     * @throws InvalidAnalyticsRangeException for a range that ends before it starts or is
     *     longer than {@link #MAX_DAYS} days
     * @throws az.ideanest.shared.money.CurrencyMismatchException if a campaign somehow
     *     holds rolled-up days in two currencies. Deliberately not caught, and
     *     deliberately not a 400: there is no answer that would be true, and one of the
     *     two reported quietly would be worse than a failure. V27 refuses to write such a
     *     campaign at all, so reaching this means the refusal was bypassed
     */
    @Transactional(readOnly = true)
    public ProjectAnalytics of(UUID projectId, UUID accountId, LocalDate from, LocalDate to) {
        projects.requireEditableLocks(projectId, accountId);

        ZoneId zone = properties.zone();
        LocalDate last = to == null ? RollupWindow.dayOf(clock.instant(), zone) : to;
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1L) : from;
        // Refuses through the same value object the writer uses, so "a range" means one
        // thing in this feature rather than two.
        RollupWindow range = requested(zone, first, last);

        List<DailyAnalyticsRow> rows = rollups.daysOf(projectId, range.firstDay(), range.lastDay());
        if (rows.isEmpty()) {
            return ProjectAnalytics.empty(zone, range.firstDay(), range.lastDay());
        }

        String currency = oneCurrencyOf(rows);
        Map<LocalDate, List<DailyChannelRow>> channels =
                rollups.channelsOf(projectId, range.firstDay(), range.lastDay()).stream()
                        .collect(Collectors.groupingBy(DailyChannelRow::day));

        List<ProjectAnalytics.Day> days = new ArrayList<>(rows.size());
        Instant newest = null;
        for (DailyAnalyticsRow row : rows) {
            newest = newest == null || row.computedAt().isAfter(newest) ? row.computedAt() : newest;
            days.add(new ProjectAnalytics.Day(
                    row.day(),
                    row.timeZone(),
                    row.pledgeCount(),
                    Money.of(row.amount(), row.currency()),
                    row.cumulativePledgeCount(),
                    Money.of(row.cumulativeAmount(), row.currency()),
                    channelsOf(channels.get(row.day()), row.currency())));
        }

        return new ProjectAnalytics(zone, range.firstDay(), range.lastDay(), currency, newest, days);
    }

    /**
     * The requested range, refused when it is not one this endpoint answers.
     *
     * <p>Both refusals are the caller's to fix, which is why they are
     * {@link InvalidAnalyticsRangeException} and become a 400. A range running backwards
     * is corrected by nobody: silently swapping the ends would return a plausible answer
     * for a question that was not asked.
     */
    private static RollupWindow requested(ZoneId zone, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidAnalyticsRangeException(
                    "A range ends on or after the day it starts, and this one runs " + from + " to " + to);
        }
        RollupWindow range = RollupWindow.of(zone, from, to);
        if (range.days() > MAX_DAYS) {
            throw new InvalidAnalyticsRangeException("A range covers at most " + MAX_DAYS
                    + " days, and this one covers " + range.days() + ". Ask for it in parts.");
        }
        return range;
    }

    /**
     * The currency every row is in.
     *
     * <p>Checked rather than taken from the first row. A campaign's pledges are all in
     * its own currency (§7.3) and V27 refuses to roll up one whose attributions are not,
     * so this is an invariant being asserted at the last place it could still be caught —
     * and the failure is loud for {@code CurrencyMismatchException}'s reason: a trend
     * silently reported in one of two currencies is a number somebody would act on.
     */
    private static String oneCurrencyOf(List<DailyAnalyticsRow> rows) {
        Set<String> currencies =
                rows.stream().map(DailyAnalyticsRow::currency).collect(Collectors.toCollection(HashSet::new));
        if (currencies.size() > 1) {
            List<String> named = currencies.stream().sorted().toList();
            throw new CurrencyMismatchException(named.get(0), named.get(1));
        }
        return currencies.iterator().next();
    }

    /**
     * A day's channel split, most valuable first.
     *
     * <p>Ordered here rather than by the query for §4.7's reason: a creator reading a
     * split is asking which channel brought the most money, not which sorts first
     * alphabetically. Ties fall to the count and then to the channel's name, so the order
     * is total and two reads of the same day do not shuffle.
     */
    private static List<ProjectAnalytics.ChannelTotal> channelsOf(List<DailyChannelRow> rows, String currency) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new ProjectAnalytics.ChannelTotal(
                        row.channel(), row.pledgeCount(), Money.of(row.amount(), currency)))
                .sorted(Comparator.comparing(ProjectAnalytics.ChannelTotal::amount)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(ProjectAnalytics.ChannelTotal::pledgeCount)
                                .reversed())
                        .thenComparing(total -> total.channel().name()))
                .toList();
    }
}
