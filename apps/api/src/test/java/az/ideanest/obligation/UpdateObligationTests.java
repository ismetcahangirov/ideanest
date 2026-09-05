package az.ideanest.obligation;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.obligation.domain.ObligationState;
import az.ideanest.obligation.domain.UpdateObligation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §5.5's clock and its boundaries — issue #437.
 *
 * <p>A plain unit test: none of this is about persistence. What is in the database is when a
 * campaign closed and when its creator last posted; what those two facts mean is a rule in
 * {@link UpdateObligation}, and that is exactly the thing a reviewer should be able to see change
 * in a diff.
 *
 * <p>#437's definition of done names four things to test — "the clock's boundaries, an update
 * resetting it, fulfilment completing stopping it, and a lapse escalating exactly once rather than
 * daily" — and each of them is a test below.
 */
class UpdateObligationTests {

    private static final Instant CLOSED = Instant.parse("2026-01-31T12:00:00Z");
    private static final Duration MONTH = Duration.ofDays(30);
    private static final Duration LEAD = Duration.ofDays(7);

    private static UpdateObligation opened() {
        return new UpdateObligation(UUID.randomUUID(), UUID.randomUUID(), CLOSED, MONTH);
    }

    @Test
    @DisplayName("a campaign that closed this morning is not late")
    void theFirstUpdateIsOwedAfterTheFirstMonth() {
        UpdateObligation obligation = opened();

        // The clock starts at the close and the first update is owed one interval later, which
        // is what "at least monthly" means. Starting it as already due would greet every
        // successful creator with an obligation they are behind on.
        assertThat(obligation.dueAt()).isEqualTo(CLOSED.plus(MONTH));
        assertThat(obligation.stateAt(CLOSED, LEAD)).isEqualTo(ObligationState.CURRENT);
    }

    @Test
    @DisplayName("the boundaries: current, then due soon, then lapsed, and each on its own instant")
    void theBoundaries() {
        UpdateObligation obligation = opened();
        Instant due = obligation.dueAt();

        // A second before the lead begins.
        assertThat(obligation.stateAt(due.minus(LEAD).minusSeconds(1), LEAD)).isEqualTo(ObligationState.CURRENT);
        // The lead is inclusive at its own start: seven days out is the day the warning is for.
        assertThat(obligation.stateAt(due.minus(LEAD), LEAD)).isEqualTo(ObligationState.DUE_SOON);
        // A second before the due instant is still only due soon.
        assertThat(obligation.stateAt(due.minusSeconds(1), LEAD)).isEqualTo(ObligationState.DUE_SOON);
        // And the due instant itself is the lapse. Half-open the other way would mean a
        // campaign is never late on the day it is late.
        assertThat(obligation.stateAt(due, LEAD)).isEqualTo(ObligationState.NEVER_UPDATED);
    }

    @Test
    @DisplayName("never having posted is a different fact from having posted and gone quiet")
    void neverUpdatedIsItsOwnState() {
        UpdateObligation never = opened();
        assertThat(never.stateAt(never.dueAt(), LEAD)).isEqualTo(ObligationState.NEVER_UPDATED);

        UpdateObligation posted = opened();
        posted.recordUpdate(CLOSED.plus(Duration.ofDays(10)), MONTH);

        // "Has not posted since the campaign closed" and "posted, and not recently" are the same
        // to nobody reading the page to decide whether to back this creator again.
        assertThat(posted.stateAt(posted.dueAt(), LEAD)).isEqualTo(ObligationState.LAPSED);
        assertThat(posted.lastUpdateAt()).isEqualTo(CLOSED.plus(Duration.ofDays(10)));
    }

    @Test
    @DisplayName("an update resets the clock and does not close the moderator's case")
    void anUpdateResetsTheClock() {
        UpdateObligation obligation = opened();
        Instant due = obligation.dueAt();

        assertThat(obligation.claimLapse(due)).isTrue();
        assertThat(obligation.lapsedAt()).isEqualTo(due);

        Instant posted = due.plus(Duration.ofDays(3));
        assertThat(obligation.recordUpdate(posted, MONTH)).isTrue();

        // Up to date again, and the next month runs from the update rather than from the date
        // the creator missed -- a clock that caught up would make a late creator permanently
        // late by an amount nobody could pay back.
        assertThat(obligation.dueAt()).isEqualTo(posted.plus(MONTH));
        assertThat(obligation.stateAt(posted, LEAD)).isEqualTo(ObligationState.CURRENT);

        // The escalation is NOT cleared. Posting brings the campaign up to date and does not
        // make the case unhappen: a self-emptying queue would let a creator be late every month,
        // post a sentence each time, and never be looked at. Only a moderator closes one.
        assertThat(obligation.lapsedAt()).isEqualTo(due);

        // And the next cycle can still be claimed, because the claim was cleared even though the
        // lapse was not.
        assertThat(obligation.claimLapse(obligation.dueAt())).isTrue();
    }

    @Test
    @DisplayName("an update is recorded once, however many times the outbox delivers it")
    void recordingIsIdempotentAndMonotonic() {
        UpdateObligation obligation = opened();
        Instant posted = CLOSED.plus(Duration.ofDays(10));

        assertThat(obligation.recordUpdate(posted, MONTH)).isTrue();
        // The same delivery again. OutboxMessage states redelivery as the contract rather than
        // as a caveat, and this is why this module needs no deduplication of its own.
        assertThat(obligation.recordUpdate(posted, MONTH)).isFalse();
        // And an older one, which is what a backfill or an out-of-order dispatch looks like.
        assertThat(obligation.recordUpdate(posted.minus(Duration.ofDays(2)), MONTH))
                .isFalse();
        assertThat(obligation.dueAt()).isEqualTo(posted.plus(MONTH));
    }

    @Test
    @DisplayName("fulfilment completing stops the clock, and a completed campaign never lapses again")
    void completionStopsTheClock() {
        UpdateObligation obligation = opened();
        Instant delivered = CLOSED.plus(Duration.ofDays(20));

        assertThat(obligation.close(delivered)).isTrue();
        assertThat(obligation.stateAt(obligation.dueAt().plus(Duration.ofDays(365)), LEAD))
                .isEqualTo(ObligationState.COMPLETE);

        // COMPLETE and not CURRENT: a profile that showed them the same way would be hiding the
        // difference between a creator who delivered and one who is still going.
        assertThat(obligation.stateAt(delivered, LEAD)).isNotEqualTo(ObligationState.CURRENT);

        // Idempotent, keeping the first instant: a second completion is a re-import of the same
        // fact and the earlier date is the true one.
        assertThat(obligation.close(delivered.plus(Duration.ofDays(1)))).isFalse();
        assertThat(obligation.closedAt()).isEqualTo(delivered);
    }

    @Test
    @DisplayName("a lapse escalates exactly once, not every morning")
    void aLapseEscalatesOnce() {
        UpdateObligation obligation = opened();
        Instant due = obligation.dueAt();

        assertThat(obligation.claimLapse(due)).isTrue();

        // Thirty more daily sweeps over a campaign that stays silent. The due date does not move
        // until an update moves it, so the claim keeps refusing -- which is what stops a
        // moderator's queue filling with one row per morning per silent campaign.
        for (int day = 1; day <= 30; day++) {
            assertThat(obligation.claimLapse(due.plus(Duration.ofDays(day))))
                    .withFailMessage("The lapse was claimed a second time on day %d", day)
                    .isFalse();
        }
        assertThat(obligation.lapsedAt()).isEqualTo(due);
    }

    @Test
    @DisplayName("a second lapse after an update is a new case, and re-opens a resolved one")
    void lapsingAgainReopensTheCase() {
        UpdateObligation obligation = opened();
        UUID moderator = UUID.randomUUID();
        Instant firstDue = obligation.dueAt();

        obligation.claimLapse(firstDue);
        assertThat(obligation.resolve(moderator, "Emailed the creator.", firstDue.plusSeconds(60)))
                .isTrue();
        assertThat(obligation.resolvedAt()).isNotNull();

        Instant posted = firstDue.plus(Duration.ofDays(1));
        obligation.recordUpdate(posted, MONTH);

        // Silent again a month later. The moderator who spoke to this creator in March has not
        // seen what happened in April, so the case comes back rather than staying closed.
        assertThat(obligation.claimLapse(obligation.dueAt())).isTrue();
        assertThat(obligation.resolvedAt()).isNull();
        assertThat(obligation.resolutionNote()).isNull();
    }

    @Test
    @DisplayName("there is nothing to resolve on a campaign that has not lapsed")
    void resolvingNeedsALapse() {
        UpdateObligation obligation = opened();

        // Enforced here and by `update_obligations_resolution_needs_a_lapse`. Without both, the
        // queue would be answerable by writing rows into it.
        assertThat(obligation.resolve(UUID.randomUUID(), "Nothing happened.", CLOSED))
                .isFalse();
        assertThat(obligation.resolvedAt()).isNull();
    }

    @Test
    @DisplayName("the reminder is claimed once per cycle and re-arms when the cycle moves")
    void theReminderIsClaimedPerCycle() {
        UpdateObligation obligation = opened();

        assertThat(obligation.claimReminder()).isTrue();
        assertThat(obligation.claimReminder()).isFalse();

        obligation.recordUpdate(CLOSED.plus(Duration.ofDays(25)), MONTH);

        // The claim is the due date, so moving the due date re-arms it. A boolean would have
        // needed clearing, and the release that forgot to clear it is a creator who is never
        // warned again.
        assertThat(obligation.claimReminder()).isTrue();
    }
}
