package az.ideanest.ticket.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One support conversation — V51's row, issue #310.
 *
 * <p><strong>The state and the resolution date move together, always.</strong> V51 pairs
 * them by constraint, and every transition here sets both — so there is no path on which a
 * ticket is {@code RESOLVED} with no date, or {@code OPEN} carrying one from the last time
 * it was closed. A setter per column would have made that a rule somebody has to remember.
 */
@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private UUID requesterId;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private TicketSubjectType subjectType;

    @Column(name = "subject_ref", updatable = false)
    private UUID subjectRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TicketState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private TicketPriority priority;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SupportTicket() {
        // Hibernate.
    }

    private SupportTicket(
            UUID requesterId,
            String subject,
            TicketSubjectType subjectType,
            UUID subjectRef,
            TicketPriority priority,
            Instant at) {

        this.id = Identifiers.newIdentifier();
        this.requesterId = Objects.requireNonNull(requesterId, "requesterId");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.subjectType = Objects.requireNonNull(subjectType, "subjectType");
        this.subjectRef = subjectRef;
        this.state = TicketState.OPEN;
        this.priority = Objects.requireNonNull(priority, "priority");
        this.updatedAt = at;

        // Asserted here as well as by V51's CHECK, so a caller assembling one in a test
        // finds out at the constructor rather than at the flush.
        if (subjectType.needsReference() == (subjectRef == null)) {
            throw new IllegalArgumentException(
                    "A " + subjectType + " ticket " + (subjectType.needsReference() ? "names" : "names nothing"));
        }
    }

    /** A ticket somebody has just raised. */
    public static SupportTicket raised(
            UUID requesterId,
            String subject,
            TicketSubjectType subjectType,
            UUID subjectRef,
            TicketPriority priority,
            Instant at) {

        return new SupportTicket(requesterId, subject, subjectType, subjectRef, priority, at);
    }

    /**
     * Somebody has picked it up, or handed it on.
     *
     * <p>Null unassigns it, which puts it back in the queue — V51's partial index is that
     * queue, and there is deliberately no separate table for it.
     */
    public void assign(UUID assigneeId, Instant at) {
        this.assigneeId = assigneeId;
        this.updatedAt = at;
    }

    public void prioritise(TicketPriority priority, Instant at) {
        this.priority = Objects.requireNonNull(priority, "priority");
        this.updatedAt = at;
    }

    /**
     * Moves the ticket, keeping the resolution date in step.
     *
     * <p>The one method that changes state, so V51's pairing constraint cannot be broken
     * by a caller that set one and forgot the other. Re-opening clears the date, which is
     * the ordinary case: a resolved ticket goes back to {@link TicketState#OPEN} when the
     * requester replies.
     */
    public void moveTo(TicketState state, Instant at) {
        this.state = Objects.requireNonNull(state, "state");
        this.resolvedAt = state.isResolved() ? at : null;
        this.updatedAt = at;
    }

    /**
     * A message arrived, from whichever side.
     *
     * <p>A requester's reply re-opens a resolved ticket and takes a pending one off the
     * waiting list; a staff reply moves an open one to {@code PENDING}. Both are what the
     * next person reading the queue needs to be true, and doing it in the entity means
     * neither can be forgotten by a caller that only meant to append a message.
     */
    public void answered(TicketSide side, Instant at) {
        if (side == TicketSide.REQUESTER) {
            this.state = TicketState.OPEN;
        } else if (state == TicketState.OPEN) {
            this.state = TicketState.PENDING;
        }
        this.resolvedAt = this.state.isResolved() ? this.resolvedAt : null;
        this.updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public UUID requesterId() {
        return requesterId;
    }

    public String subject() {
        return subject;
    }

    public TicketSubjectType subjectType() {
        return subjectType;
    }

    public UUID subjectRef() {
        return subjectRef;
    }

    public TicketState state() {
        return state;
    }

    public TicketPriority priority() {
        return priority;
    }

    public UUID assigneeId() {
        return assigneeId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }
}
