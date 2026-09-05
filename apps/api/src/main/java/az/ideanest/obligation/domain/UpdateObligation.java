package az.ideanest.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One campaign's §5.5 clock — V68's row, issue #437.
 *
 * <p><strong>The state is a comparison and not a column.</strong> {@link #stateAt} answers from
 * {@code dueAt} and the instant it is given, so an obligation lapses because time passed rather
 * than because a sweep ran. The sweep exists to send the reminder and to raise the escalation —
 * things that have to happen once — and not to decide what the page says. A campaign whose sweep
 * has been down for a week still reads as lapsed on its own page, which is the failure mode to
 * have.
 *
 * <p><strong>Nothing here touches money or a campaign's state</strong>, and there is no method
 * that could. §9.7 says a creator who cannot deliver "offers a refund; the platform mediates",
 * and suspension is a moderator decision under §4.11's AD-02 — so the strongest thing this
 * entity does is stop being {@code CURRENT}.
 */
@Entity
@Table(name = "update_obligations")
public class UpdateObligation {

    @Id
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "last_update_at")
    private Instant lastUpdateAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "reminded_for")
    private Instant remindedFor;

    @Column(name = "lapsed_for")
    private Instant lapsedFor;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected UpdateObligation() {
        // Hibernate.
    }

    /**
     * Opens a clock at a campaign's close.
     *
     * <p>The first update is owed one interval after the close and not immediately, which is
     * what "at least monthly" means: a creator whose campaign closed this morning is not late.
     */
    public UpdateObligation(UUID projectId, UUID creatorId, Instant openedAt, Duration interval) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.dueAt = openedAt.plus(Objects.requireNonNull(interval, "interval"));
    }

    /**
     * Records an update and starts the next month.
     *
     * <p><strong>Idempotent and monotonic.</strong> The outbox redelivers, so the same
     * publication arrives more than once; and updates arrive in the order the relay dispatches
     * them, which for one campaign is the order they were written but says nothing about a
     * backfill. Taking only instants later than the one already recorded makes both harmless.
     *
     * <p><strong>The claim is cleared and the lapse is not.</strong> A creator who lapsed and then
     * posted is up to date — {@code dueAt} moves and {@link #stateAt} goes back to
     * {@code CURRENT} — and has not made the escalation unhappen. Clearing {@code lapsedAt} here
     * would make the moderator's queue self-emptying, so a creator could be late every month,
     * post a sentence each time a case was raised, and never be looked at. Only
     * {@link #resolve} closes a case.
     *
     * @return true when this call moved the clock
     */
    public boolean recordUpdate(Instant publishedAt, Duration interval) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (lastUpdateAt != null && !publishedAt.isAfter(lastUpdateAt)) {
            return false;
        }
        this.lastUpdateAt = publishedAt;
        this.dueAt = publishedAt.plus(interval);
        // The claim is cleared so the next cycle can be claimed; the lapse itself stays, so the
        // case a moderator has not looked at is still in front of them. See the note above.
        this.lapsedFor = null;
        return true;
    }

    /**
     * Stops the clock because fulfilment is complete.
     *
     * <p>Idempotent, keeping the first instant: a second completion is a re-import of the same
     * fact, and the earlier date is the true one.
     *
     * @return true when this call closed it
     */
    public boolean close(Instant at) {
        if (closedAt != null) {
            return false;
        }
        this.closedAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /**
     * Claims this cycle's reminder.
     *
     * <p>The claim is the due date, so a cycle can be claimed once and a new cycle re-arms it
     * without anything having to clear a flag. V68's header argues why that is a column rather
     * than a boolean.
     *
     * @return true when this call claimed it
     */
    public boolean claimReminder() {
        if (dueAt.equals(remindedFor)) {
            return false;
        }
        this.remindedFor = dueAt;
        return true;
    }

    /**
     * Claims this cycle's lapse — the escalation to a moderator.
     *
     * <p>Once per cycle rather than daily, which is #437's test by name. The due date does not
     * move until an update moves it, so a campaign that stays silent stays escalated on the one
     * row a moderator already has, instead of producing a queue entry every morning.
     *
     * @return true when this call claimed it
     */
    public boolean claimLapse(Instant at) {
        if (dueAt.equals(lapsedFor)) {
            return false;
        }
        this.lapsedFor = dueAt;
        this.lapsedAt = Objects.requireNonNull(at, "at");
        // A new lapse re-opens the case even if an earlier one was resolved: the moderator who
        // spoke to this creator in March has not seen what happened in April.
        this.resolvedAt = null;
        this.resolvedBy = null;
        this.resolutionNote = null;
        return true;
    }

    /**
     * A moderator has seen the escalation and closed it.
     *
     * <p><strong>Not the end of the obligation.</strong> The clock keeps running and a creator
     * who lapses again lapses again — {@link #claimLapse} re-opens the case. What this records
     * is that a human being looked, which is the only thing #437 asks the queue to guarantee.
     *
     * @return true when this call resolved it
     */
    public boolean resolve(UUID moderatorId, String note, Instant at) {
        if (lapsedAt == null || resolvedAt != null) {
            return false;
        }
        this.resolvedBy = Objects.requireNonNull(moderatorId, "moderatorId");
        this.resolutionNote = Objects.requireNonNull(note, "note");
        this.resolvedAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /**
     * What this campaign's page and its creator's profile say.
     *
     * @param reminderLead how long before the due date {@link ObligationState#DUE_SOON} begins.
     *     A parameter rather than a constant because it is the same configured value the sweep
     *     sends the reminder on, and two numbers that must agree should be one number
     */
    public ObligationState stateAt(Instant at, Duration reminderLead) {
        if (closedAt != null) {
            return ObligationState.COMPLETE;
        }
        if (at.isBefore(dueAt)) {
            return at.isBefore(dueAt.minus(reminderLead)) ? ObligationState.CURRENT : ObligationState.DUE_SOON;
        }
        return lastUpdateAt == null ? ObligationState.NEVER_UPDATED : ObligationState.LAPSED;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID creatorId() {
        return creatorId;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant lastUpdateAt() {
        return lastUpdateAt;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant lapsedAt() {
        return lapsedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public UUID resolvedBy() {
        return resolvedBy;
    }

    public String resolutionNote() {
        return resolutionNote;
    }

    public Instant closedAt() {
        return closedAt;
    }
}
