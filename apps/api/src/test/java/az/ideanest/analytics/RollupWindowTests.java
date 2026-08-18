package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.analytics.domain.RollupWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a day starts, which is the decision the whole rollup rests on.
 *
 * <p>Deliberately a plain unit test: calendar arithmetic needs no container, and a rule
 * about time that can only be checked with a database is a rule nobody checks.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aPledgeAfterMidnightInBakuBelongsToTheNewDay()} — the four hours a UTC
 *       rollup would report against the wrong day, which on this platform is the tail of
 *       the evening.
 *   <li>{@link #theEndOfAWindowIsTheStartOfTheNextDay()} — half-open, because "the last
 *       instant of a day" does not exist at any resolution PostgreSQL stores and the
 *       version of that bug which ships loses a pledge at 23:59:59.999999.
 *   <li>{@link #aDayWhoseMidnightDoesNotExistStartsWhenItCan()} — the reason this goes
 *       through {@link ZoneId} rather than a fixed offset, even though the platform's
 *       own zone has had no daylight saving since 2016.
 * </ul>
 */
class RollupWindowTests {

    /** The platform's calendar: UTC+4, no daylight saving since 2016. */
    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    @Test
    @DisplayName("a pledge after midnight in Baku belongs to the new day, not to the UTC one")
    void aPledgeAfterMidnightInBakuBelongsToTheNewDay() {
        // 00:30 on the 11th in Baku. A UTC rollup would file it under the 10th, and a
        // creator comparing the dashboard against their own calendar would find it
        // disagreed with nothing on screen explaining why.
        Instant justAfterMidnight = Instant.parse("2026-03-10T20:30:00Z");

        assertThat(RollupWindow.dayOf(justAfterMidnight, BAKU)).isEqualTo(LocalDate.of(2026, 3, 11));
        assertThat(RollupWindow.dayOf(justAfterMidnight, ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    @DisplayName("a pledge before midnight in Baku stays on the day it was made")
    void aPledgeBeforeMidnightInBakuStaysOnItsDay() {
        // 23:30 on the 10th in Baku. The other side of the same boundary, asserted so
        // that a sign error in the conversion cannot pass by moving everything.
        assertThat(RollupWindow.dayOf(Instant.parse("2026-03-10T19:30:00Z"), BAKU))
                .isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    @DisplayName("a window starts at midnight and ends at the next day's midnight")
    void theEndOfAWindowIsTheStartOfTheNextDay() {
        RollupWindow window = RollupWindow.of(BAKU, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 12));

        // Baku is UTC+4, so a local midnight is 20:00 the previous day in UTC.
        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-03-09T20:00:00Z"));
        // Exclusive: the first instant of the 13th, not the last instant of the 12th.
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-03-12T20:00:00Z"));
        assertThat(window.days()).isEqualTo(3);
    }

    @Test
    @DisplayName("the day a pledge is on is inside the window that covers it, at both edges")
    void bothEdgesOfAWindowAreCovered() {
        RollupWindow window = RollupWindow.of(BAKU, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10));

        // The first instant of the day is in, the first instant of the next day is out.
        // Asserted as instants rather than as dates because that is the form the query
        // uses, and it is the form the off-by-one would be in.
        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-03-09T20:00:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-03-10T20:00:00Z"));
        assertThat(RollupWindow.dayOf(window.startInclusive(), BAKU)).isEqualTo(window.firstDay());
        assertThat(RollupWindow.dayOf(window.endExclusive().minusNanos(1_000), BAKU))
                .isEqualTo(window.lastDay());
    }

    @Test
    @DisplayName("a re-rollup window of three days covers today and the three before it")
    void aWindowEndingTodayReachesBackwards() {
        RollupWindow window = RollupWindow.endingOn(Instant.parse("2026-03-10T12:00:00Z"), BAKU, 3);

        assertThat(window.lastDay()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(window.firstDay()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(window.days()).isEqualTo(4);
    }

    @Test
    @DisplayName("a re-rollup window of zero days is the day in progress, not nothing")
    void aWindowOfZeroDaysIsStillADay() {
        // A deployment that has decided its events are never late asks for this. It must
        // mean "recompute today", because "recompute nothing" is a job that has stopped.
        RollupWindow window = RollupWindow.endingOn(Instant.parse("2026-03-10T12:00:00Z"), BAKU, 0);

        assertThat(window.days()).isEqualTo(1);
        assertThat(window.firstDay()).isEqualTo(window.lastDay());
    }

    @Test
    @DisplayName("every day of a daylight-saving year maps back to itself and meets the next one")
    void daylightSavingDoesNotLoseOrDuplicateADay() {
        // Chile is the hard case: its transitions happen at midnight, so there are days
        // there whose 00:00 never occurred. That is the whole reason RollupWindow goes
        // through ZoneId rather than a fixed offset, and asserting it as a property over
        // a year is better than naming a date — a rule table that moves would otherwise
        // turn a correct implementation into a red test.
        ZoneId santiago = ZoneId.of("America/Santiago");

        LocalDate day = LocalDate.of(2026, 1, 1);
        while (day.getYear() == 2026) {
            RollupWindow window = RollupWindow.of(santiago, day, day);

            assertThat(RollupWindow.dayOf(window.startInclusive(), santiago))
                    .withFailMessage("The first instant of %s is not on %s", day, day)
                    .isEqualTo(day);
            assertThat(window.startInclusive()).isBefore(window.endExclusive());
            // No gap and no overlap: one day's exclusive end is the next day's start, so
            // no pledge can fall between two buckets or into both.
            assertThat(window.endExclusive())
                    .isEqualTo(RollupWindow.of(santiago, day.plusDays(1), day.plusDays(1))
                            .startInclusive());
            day = day.plusDays(1);
        }
    }

    @Test
    @DisplayName("a window that ends before it starts is refused rather than swapped")
    void aBackwardsWindowIsRefused() {
        // Corrected for, it would return a plausible answer to a question nobody asked.
        assertThatThrownBy(() -> RollupWindow.of(BAKU, LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-03-12");
    }

    @Test
    @DisplayName("a window cannot reach forwards from the day it ends on")
    void aWindowCannotReachForwards() {
        assertThatThrownBy(() -> RollupWindow.endingOn(Instant.parse("2026-03-10T12:00:00Z"), BAKU, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
