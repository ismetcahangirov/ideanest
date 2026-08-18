package az.ideanest.analytics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A range of calendar days, and the two instants that bound it.
 *
 * <p><strong>The one place "a day" is decided.</strong> The rollup writes rows keyed by
 * a date, the read side asks for a range of dates, and PostgreSQL buckets a
 * {@code timestamptz} into one with {@code AT TIME ZONE}. Three places that have to
 * agree about where a day starts, and the way they agree is that all three take the
 * boundaries from here.
 *
 * <p><strong>Why the boundaries are computed in Java rather than in the predicate.</strong>
 * A query could say {@code WHERE (pledged_at AT TIME ZONE 'Asia/Baku')::date BETWEEN …}
 * and be correct. It would also be unindexable: an expression over the column is not a
 * range over the column, so the planner has a sequential scan and nothing to do about
 * it. Turning the dates into instants here means the scan is
 * {@code pledged_at >= … AND pledged_at < …}, which is exactly what
 * {@code referral_attributions_pledged_at_idx} answers. The conversion is the whole of
 * the difference between a rollup that costs a range scan and one that costs the table.
 *
 * <p><strong>Half-open, and it matters.</strong> {@link #startInclusive()} is the first
 * instant of {@link #firstDay()} and {@link #endExclusive()} is the first instant of the
 * day <em>after</em> {@link #lastDay()}. A closed upper bound would need "the last
 * instant of the day", which does not exist at any resolution PostgreSQL stores — and
 * the version of that bug that ships is the one where a pledge at 23:59:59.999999 falls
 * out of its own day.
 *
 * <p>Boundaries go through {@link ZoneId#getRules()} by way of {@code atStartOfDay},
 * which is what makes this correct in a zone that observes daylight saving: on a day
 * whose midnight does not exist, {@code atStartOfDay} returns the first instant that
 * does rather than a wall clock reading nobody experienced. Azerbaijan has observed no
 * daylight saving since 2016, so this costs nothing here and is what stops the code
 * being wrong somewhere else.
 *
 * @param zone the calendar these days belong to
 * @param firstDay the earliest day in the range, included
 * @param lastDay the latest day in the range, included
 */
public record RollupWindow(ZoneId zone, LocalDate firstDay, LocalDate lastDay) {

    public RollupWindow {
        Objects.requireNonNull(zone, "A day belongs to a calendar, and a calendar is a zone");
        Objects.requireNonNull(firstDay, "A window starts on some day");
        Objects.requireNonNull(lastDay, "A window ends on some day");

        if (lastDay.isBefore(firstDay)) {
            // Refused rather than swapped. A caller that has its dates the wrong way
            // round has a bug, and a range quietly corrected for it is a bug that
            // returns plausible numbers for the wrong month.
            throw new IllegalArgumentException(
                    "A window ends on or after the day it starts, and this one runs " + firstDay + " to " + lastDay);
        }
    }

    /**
     * The day {@code at} falls on, in {@code zone}.
     *
     * <p>"Today" for the creator this dashboard is for, which is not the same day as
     * "today" in UTC for four hours out of every twenty-four — and those are the four
     * hours after midnight, when a campaign's evening traffic is still arriving.
     */
    public static LocalDate dayOf(Instant at, ZoneId zone) {
        return LocalDate.ofInstant(at, zone);
    }

    /**
     * The day {@code at} falls on, and the {@code days} before it.
     *
     * <p>What a scheduled pass recomputes: the day in progress, plus enough history to
     * absorb an attribution that arrived after its own day closed. {@code days} of zero
     * is one day — the one in progress — rather than none.
     */
    public static RollupWindow endingOn(Instant at, ZoneId zone, long days) {
        if (days < 0) {
            throw new IllegalArgumentException("A window reaches backwards, and " + days + " days is forwards");
        }
        LocalDate today = dayOf(at, zone);
        return new RollupWindow(zone, today.minusDays(days), today);
    }

    /** An explicit range, for a backfill somebody is running on purpose. */
    public static RollupWindow of(ZoneId zone, LocalDate firstDay, LocalDate lastDay) {
        return new RollupWindow(zone, firstDay, lastDay);
    }

    /** The first instant of {@link #firstDay()}. */
    public Instant startInclusive() {
        return firstDay.atStartOfDay(zone).toInstant();
    }

    /**
     * The first instant of the day after {@link #lastDay()}.
     *
     * <p>Exclusive. See the class comment for why the alternative is a bug rather than
     * a preference.
     */
    public Instant endExclusive() {
        return lastDay.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** How many days the window covers, counting both ends. */
    public long days() {
        return lastDay.toEpochDay() - firstDay.toEpochDay() + 1;
    }

    @Override
    public String toString() {
        return firstDay + "…" + lastDay + " (" + zone.getId() + ")";
    }
}
