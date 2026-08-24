package az.ideanest.ticket.domain;

/**
 * Where a support conversation has got to - V51, issue #310.
 *
 * <p><strong>RESOLVED and CLOSED are separate, and V51 says why:</strong> "we answered and
 * heard nothing" and "we answered and they were satisfied" are different numbers in a
 * support report, and a single terminal state would make the first one invisible.
 *
 * <p>The machine is not a line. A resolved ticket goes back to {@link #OPEN} when the
 * requester replies, which is the ordinary case rather than an edge one - so nothing here
 * asserts that a ticket only moves forward.
 */
public enum TicketState {

    /** Waiting on us. The queue. */
    OPEN,

    /** Waiting on the requester. */
    PENDING,

    /** Answered, and they said so or stopped replying happily. */
    RESOLVED,

    /** Answered, and nobody came back. Terminal by timeout rather than by agreement. */
    CLOSED;

    /** Whether this ticket is still somebody's work. V51's partial index. */
    public boolean isOpen() {
        return this == OPEN || this == PENDING;
    }

    /** Whether it carries a resolution date. V51 pairs the two by constraint. */
    public boolean isResolved() {
        return this == RESOLVED || this == CLOSED;
    }
}
