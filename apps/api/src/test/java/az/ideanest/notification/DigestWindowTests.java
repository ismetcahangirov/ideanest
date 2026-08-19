package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.notification.domain.DigestWindow;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a digest period closes, which is the whole of the cadence decision.
 *
 * <p>No database and no context: {@code DigestWindow} decides nothing from configuration and
 * reads nothing, which is what lets this exercise it across a midnight and a daylight saving
 * boundary in milliseconds. {@code RollupWindowTests} is the same shape for the same reason.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aTickAfterTheHourStillClosesThatHoursPeriod()} — the reason
 *       {@code lastClosedAt} is a lower bound rather than an equality. Without it a single
 *       missed tick delays a whole day's digest by a whole day, silently.
 *   <li>{@link #aTickBeforeTheHourClosesYesterdays()} — the other side of the same property:
 *       something held this morning must not go out in a digest reckoned from yesterday.
 *   <li>{@link #theHourIsLocalAndNotUtc()} — why the zone is a property at all. Baku is UTC+4,
 *       so a fixed UTC hour is a different hour of somebody's day.
 * </ul>
 */
class DigestWindowTests {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    /** A zone that observes daylight saving, so the arithmetic is exercised where it can be wrong. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static final int EIGHT = 8;

    @Test
    @DisplayName("a tick exactly on the hour closes that hour's period, not yesterday's")
    void aTickOnTheHourClosesItsOwnPeriod() {
        // 08:00 in Baku is 04:00 UTC.
        Instant onTheHour = Instant.parse("2026-08-19T04:00:00Z");

        assertThat(DigestWindow.at(BAKU, EIGHT).lastClosedAt(onTheHour))
                .as("a job firing at 08:00:00 sends today's digest rather than waiting an hour")
                .isEqualTo(onTheHour);
    }

    /**
     * The self-healing property, and the reason for the whole design.
     *
     * <p>If this were an equality — "is the local hour eight" — a tick lost to a deployment
     * would push the digest a full day with nothing anywhere recording that it had been
     * skipped.
     */
    @Test
    @DisplayName("a tick after the hour still closes that hour's period")
    void aTickAfterTheHourStillClosesThatHoursPeriod() {
        Instant eight = Instant.parse("2026-08-19T04:00:00Z");

        assertThat(DigestWindow.at(BAKU, EIGHT).lastClosedAt(Instant.parse("2026-08-19T05:30:00Z")))
                .as("09:30 local: 08:00 is still the most recently closed period, so its work goes out")
                .isEqualTo(eight);
        assertThat(DigestWindow.at(BAKU, EIGHT).lastClosedAt(Instant.parse("2026-08-19T19:59:59Z")))
                .as("23:59 local, a whole day of missed ticks later, and it is still due")
                .isEqualTo(eight);
    }

    @Test
    @DisplayName("a tick before the hour closes yesterday's period")
    void aTickBeforeTheHourClosesYesterdays() {
        // 07:00 in Baku is 03:00 UTC. Yesterday's eight is the most recent one.
        assertThat(DigestWindow.at(BAKU, EIGHT).lastClosedAt(Instant.parse("2026-08-19T03:00:00Z")))
                .as("something held at 07:00 waits for today's period to close, which is what daily means")
                .isEqualTo(Instant.parse("2026-08-18T04:00:00Z"));
    }

    /**
     * Why the zone is configured rather than assumed.
     *
     * <p>{@code RollupWindow} makes this argument for a reported day; here it is about when it
     * is reasonable to send somebody mail, which is the same arithmetic and a different decision.
     */
    @Test
    @DisplayName("the digest hour is a local hour, so the same instant closes different periods in different zones")
    void theHourIsLocalAndNotUtc() {
        Instant at = Instant.parse("2026-08-19T06:00:00Z");

        assertThat(DigestWindow.at(BAKU, EIGHT).lastClosedAt(at))
                .as("10:00 in Baku: today's eight has passed")
                .isEqualTo(Instant.parse("2026-08-19T04:00:00Z"));
        assertThat(DigestWindow.at(ZoneId.of("UTC"), EIGHT).lastClosedAt(at))
                .as("06:00 UTC: today's eight has not")
                .isEqualTo(Instant.parse("2026-08-18T08:00:00Z"));
    }

    /**
     * The local hour is kept across a daylight saving change rather than the offset.
     *
     * <p>Azerbaijan has observed no daylight saving since 2016, so this costs nothing today and
     * is what stops the code being wrong somewhere else. Berlin moved to summer time on 29 March
     * 2026, so the digest hour is 07:00 UTC before it and 06:00 UTC after — the same hour of
     * somebody's morning either way, which is the point.
     */
    @Test
    @DisplayName("the local hour survives a daylight saving change; the offset does not")
    void theLocalHourSurvivesADaylightSavingChange() {
        DigestWindow window = DigestWindow.at(BERLIN, EIGHT);

        assertThat(window.lastClosedAt(Instant.parse("2026-03-28T12:00:00Z")))
                .as("winter time: 08:00 local is 07:00 UTC")
                .isEqualTo(Instant.parse("2026-03-28T07:00:00Z"));
        assertThat(window.lastClosedAt(Instant.parse("2026-03-30T12:00:00Z")))
                .as("summer time: 08:00 local is 06:00 UTC, and it is still 08:00 local")
                .isEqualTo(Instant.parse("2026-03-30T06:00:00Z"));
    }

    @Test
    @DisplayName("midnight is a digest hour and twenty-four is not")
    void theHourIsAnHourOfTheDay() {
        assertThat(DigestWindow.at(BAKU, 0).lastClosedAt(Instant.parse("2026-08-19T12:00:00Z")))
                .as("00:00 local on 19 August is 20:00 UTC on the 18th")
                .isEqualTo(Instant.parse("2026-08-18T20:00:00Z"));

        assertThatThrownBy(() -> DigestWindow.at(BAKU, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hour of the day");
        assertThatThrownBy(() -> DigestWindow.at(BAKU, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
