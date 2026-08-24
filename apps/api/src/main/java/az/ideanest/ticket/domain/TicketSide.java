package az.ideanest.ticket.domain;

/**
 * Who wrote a message - V51, issue #310.
 *
 * <p><strong>Stored rather than derived from the author.</strong> Deriving it would mean
 * asking "is this person staff" at render time, and the answer changes: somebody who
 * answered a ticket in March and has since left would have their replies re-attributed to
 * the requester's side. Worse, a member of staff can be a requester on their own ticket,
 * and then every message on it is from the same person on two different sides.
 */
public enum TicketSide {

    /** The person who asked. */
    REQUESTER,

    /** The platform. */
    STAFF
}
