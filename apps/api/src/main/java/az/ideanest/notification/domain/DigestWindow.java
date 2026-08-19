package az.ideanest.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * When a digest goes out, and therefore what belongs in one.
 *
 * <h2>The decision this record is</h2>
 *
 * <p>§4.10 offers a digest and §12.2 says a scheduled job combines held notifications into
 * one message; neither says how often, and #244 states plainly that the cadence is a
 * product decision. <strong>The decision taken here: once a day, at a fixed hour in one
 * platform zone — {@code ideanest.notification.digest.at-hour} in
 * {@code ideanest.notification.digest.zone}, 08:00 in {@code Asia/Baku}.</strong>
 *
 * <p>Daily rather than hourly, because an hourly digest of the notifications somebody chose
 * not to be told about immediately is the thing they were opting out of, arriving slightly
 * later. Daily rather than weekly, because §4.10's rows are transactional — a payment
 * refused, a campaign closing — and a week is long enough that the digest can arrive after
 * the thing it is about stopped being actionable.
 *
 * <p>A zone rather than UTC, for the reason {@code RollupWindow} spells out at length: Baku
 * is UTC+4, so a fixed UTC hour is a different local hour, and the whole point of choosing
 * an hour is that it is a reasonable time of day for the person receiving it. The property
 * is a second one rather than a reuse of {@code ideanest.analytics.aggregation.zone} —
 * #244 notes they interact — because they are different decisions that happen to have the
 * same answer: one is "whose calendar a reported day belongs to" and the other is "when it
 * is polite to send mail". Coupling them would mean a deployment that moved its reporting
 * calendar silently moved everybody's digest hour.
 *
 * <h2>{@link #lastClosedAt} is a lower bound, not an equality, and that is the design</h2>
 *
 * <p>The obvious implementation of "daily at 08:00" is an hourly job that asks whether the
 * local hour is eight and does nothing otherwise. It is also the implementation in which a
 * single missed tick — a deployment, a failed lease, a replica restarting — delays a whole
 * day's digest by a whole day, silently, because nothing afterwards remembers that 08:00
 * happened.
 *
 * <p>So the question this record answers instead is "which digest period has most recently
 * closed", and the job sends everything held from before that instant. The consequences are
 * worth being explicit about:
 *
 * <ul>
 *   <li><strong>It is self-healing.</strong> A tick at 09:00 that finds work left over from
 *       08:00 sends it, because 08:00 is still the most recently closed period. The cadence
 *       of the cron therefore decides how prompt a digest is and never whether it is sent.
 *   <li><strong>A notification is never sent before its period closes.</strong> Something
 *       held at 07:00 is not in the digest that goes out at 08:00 <em>of the previous
 *       day</em>'s reckoning — it waits for tomorrow's, which is what "daily" means.
 *   <li><strong>The bound is on {@code occurred_at}, not on when the row was written.</strong>
 *       An event redelivered late still describes something old, and a digest is a summary
 *       of what happened rather than of when the platform got round to it.
 * </ul>
 *
 * <p>A record rather than a bean: it decides nothing from configuration and reads nothing,
 * so a test can exercise it across a midnight without a context.
 *
 * @param zone the calendar the hour belongs to
 * @param hour the local hour a digest goes out at, 0 to 23
 */
public record DigestWindow(ZoneId zone, int hour) {

    /** Midnight through to the hour before midnight. */
    private static final int LATEST_HOUR = 23;

    public DigestWindow {
        Objects.requireNonNull(zone, "A digest hour belongs to a calendar, and a calendar is a zone");
        if (hour < 0 || hour > LATEST_HOUR) {
            throw new IllegalArgumentException("A digest goes out at an hour of the day, and " + hour + " is not one");
        }
    }

    public static DigestWindow at(ZoneId zone, int hour) {
        return new DigestWindow(zone, hour);
    }

    /**
     * The most recent instant at which the local clock read {@link #hour}:00.
     *
     * <p>Everything held from before this belongs to a digest period that has closed and is
     * due now. Everything held after it belongs to the period in progress.
     *
     * <p>Resolved through {@link ZonedDateTime#of} rather than by adding a fixed offset, so
     * that a zone observing daylight saving is handled the way {@code RollupWindow} handles
     * a missing midnight: on the day the digest hour does not exist, the platform's own
     * resolution gives the first instant that does, rather than a wall clock reading nobody
     * experienced. Azerbaijan has observed no daylight saving since 2016, so this costs
     * nothing today and is what stops the code being wrong somewhere else.
     *
     * @param now the instant to reckon from, from the injected {@code Clock}
     */
    public Instant lastClosedAt(Instant now) {
        Objects.requireNonNull(now, "A period closes relative to some moment");
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime today = local.toLocalDate().atTime(hour, 0).atZone(zone);

        // Strictly after, so that the tick landing exactly on the hour closes that hour's
        // period rather than yesterday's. A digest job firing at 08:00:00 should send
        // today's digest, not wait an hour for the comparison to become true.
        return today.toInstant().isAfter(now)
                ? previousDay(local.toLocalDate()).toInstant()
                : today.toInstant();
    }

    private ZonedDateTime previousDay(LocalDate today) {
        return today.minusDays(1).atTime(hour, 0).atZone(zone);
    }

    @Override
    public String toString() {
        return String.format("%02d:00 (%s)", hour, zone.getId());
    }
}
