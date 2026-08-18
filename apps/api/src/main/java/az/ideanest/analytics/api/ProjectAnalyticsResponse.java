package az.ideanest.analytics.api;

import az.ideanest.analytics.application.ProjectAnalytics;
import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * §4.7's CD-01 and CD-02 on the wire, and the contract #96 and #99 are being built
 * against.
 *
 * <p>Money is a {@link Money}, so §10.3's rule applies without this file restating it:
 * an amount crosses the API as a string inside {@code {"amount", "currency"}} and never
 * as a JSON number. A chart that received a double would round somebody's pledge on the
 * way to a pixel, which is the least visible and most expensive way for this to be
 * wrong.
 *
 * <p>Dates are plain {@code YYYY-MM-DD} rather than instants, because a day is what the
 * table holds and an instant would invite a client to render it in its own zone and get
 * a different answer from the one the creator's dashboard shows. Which calendar the days
 * belong to is {@link #timeZone()}, stated rather than assumed.
 *
 * <p><strong>There is no backer anywhere in this body</strong>, and no field that could
 * hold one — the rollup is derived from {@code referral_attributions}, which has nowhere
 * to put one. {@code ReferralReport} makes the argument.
 *
 * @param timeZone the calendar {@link #from()} and {@link #to()} were resolved in
 * @param currency what every figure is in, absent when the range holds nothing
 * @param computedAt when the newest day in the range was last recomputed, absent when
 *     there are none. <strong>Returned on purpose</strong>: it is the only thing that
 *     lets a dashboard tell "this campaign was quiet" from "the aggregator stopped on
 *     Tuesday", and a client that shows the second as the first is worse than one that
 *     shows nothing
 * @param days ascending, and <strong>sparse</strong>: a day on which the campaign took
 *     nothing has no entry. The cumulative figures on each day are what make that
 *     harmless — a line is read off them rather than accumulated across the array
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectAnalyticsResponse(
        String timeZone,
        LocalDate from,
        LocalDate to,
        String currency,
        Instant computedAt,
        List<Day> days) {

    public static ProjectAnalyticsResponse of(ProjectAnalytics analytics) {
        return new ProjectAnalyticsResponse(
                analytics.zone().getId(),
                analytics.from(),
                analytics.to(),
                analytics.currency(),
                analytics.computedAt(),
                analytics.days().stream().map(Day::of).toList());
    }

    /**
     * One campaign-day.
     *
     * @param timeZone the calendar this row was bucketed under when it was written,
     *     which is the platform's on every row a current deployment produced. Sent
     *     per-day rather than only at the top so that a range spanning a reconfiguration
     *     says so instead of quietly mixing two calendars
     */
    public record Day(
            LocalDate day,
            String timeZone,
            long pledgeCount,
            Money amount,
            long cumulativePledgeCount,
            Money cumulativeAmount,
            List<ChannelTotal> channels) {

        static Day of(ProjectAnalytics.Day day) {
            return new Day(
                    day.day(),
                    day.timeZone(),
                    day.pledgeCount(),
                    day.amount(),
                    day.cumulativePledgeCount(),
                    day.cumulativeAmount(),
                    day.channels().stream().map(ChannelTotal::of).toList());
        }
    }

    /** One channel's part of one day, most valuable first in the array. */
    public record ChannelTotal(String channel, long pledgeCount, Money amount) {

        static ChannelTotal of(ProjectAnalytics.ChannelTotal total) {
            return new ChannelTotal(total.channel().name(), total.pledgeCount(), total.amount());
        }
    }
}
