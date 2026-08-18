package az.ideanest.analytics.application;

import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * §4.7's CD-01 and CD-02, read off the rollup: what a campaign took, day by day.
 *
 * <p><strong>This is the shape #96 and #99 build on</strong>, so what is and is not in
 * it is a contract rather than a convenience:
 *
 * <ul>
 *   <li><strong>Every amount is a {@link Money}</strong>, which means it crosses the API
 *       as a string inside {@code {"amount", "currency"}} and never as a JSON number.
 *       §10.3, and CLAUDE.md's rule about floating point, applied at the one boundary
 *       where a chart library would otherwise be handed a double.
 *   <li><strong>Every day carries its own running total.</strong> A day on which a
 *       campaign took nothing has no row at all — V27's header says why writing zeroes
 *       for every campaign for every day is the worse table — so a client that added the
 *       rows up would be right and a client that read a line off them would be too.
 *       Carrying the cumulative means neither has to know which.
 *   <li><strong>The zone is stated, twice.</strong> {@link #zone()} is the calendar the
 *       range was interpreted in; {@link Day#timeZone()} is the calendar each stored row
 *       was bucketed under. They are the same on every row a current deployment wrote,
 *       and the day they are not, a client can see it rather than infer it from numbers
 *       that moved.
 *   <li><strong>Nothing names a backer</strong>, for {@code ReferralReport}'s reason and
 *       by the same construction: the source these rows are aggregated from has no
 *       column that could hold one.
 * </ul>
 *
 * @param zone the calendar {@link #from()} and {@link #to()} were resolved in
 * @param currency what every figure is in, or null when the range holds nothing. Null
 *     rather than the campaign's currency, following {@code ReferralReport}: "this
 *     campaign took nothing in these days" and "it took zero manat" are different
 *     sentences and only the first one is true
 * @param computedAt when the newest row in the range was last recomputed, or null when
 *     there are none. <strong>The freshness signal</strong>, and the only thing that
 *     lets a dashboard tell a quiet week from an aggregator that stopped running
 * @param days ascending. Days with no pledges are absent, not zero
 */
public record ProjectAnalytics(
        ZoneId zone, LocalDate from, LocalDate to, String currency, Instant computedAt, List<Day> days) {

    public ProjectAnalytics {
        days = List.copyOf(days);
    }

    /** Nothing in this range, which is an answer and not an absence of one. */
    public static ProjectAnalytics empty(ZoneId zone, LocalDate from, LocalDate to) {
        return new ProjectAnalytics(zone, from, to, null, null, List.of());
    }

    /**
     * One campaign-day.
     *
     * @param timeZone the calendar this row was bucketed under, as stored
     * @param pledgeCount how many confirmed pledges landed. Also the day's backer count:
     *     {@code pledges_project_backer_active_key} allows a backer one active pledge per
     *     campaign, so the two are equal — and it is named for what is counted, because a
     *     field called {@code backerCount} would be a claim the source cannot support
     * @param cumulativeAmount the campaign's total up to and including this day
     * @param channels where the day's pledges came from, most valuable first. The
     *     channel only — the finer referral labels are unbounded free text and stay in
     *     {@code GET /referrers}, which folds them at read time
     */
    public record Day(
            LocalDate day,
            String timeZone,
            long pledgeCount,
            Money amount,
            long cumulativePledgeCount,
            Money cumulativeAmount,
            List<ChannelTotal> channels) {

        public Day {
            channels = List.copyOf(channels);
        }
    }

    /** One channel's part of one day. */
    public record ChannelTotal(ReferralChannel channel, long pledgeCount, Money amount) {
    }
}
