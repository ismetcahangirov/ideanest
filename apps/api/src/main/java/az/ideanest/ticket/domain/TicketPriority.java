package az.ideanest.ticket.domain;

/**
 * How urgently a ticket needs an answer - V51, issue #310.
 *
 * <p>Four levels, set by staff rather than by the requester. A priority the person asking
 * could choose is a priority that is URGENT on every ticket within a week, which is the
 * same as having none.
 */
public enum TicketPriority {

    /** A question. It can wait. */
    LOW,

    NORMAL,

    /** Somebody cannot do something they are entitled to do. */
    HIGH,

    /** Money is wrong, or a campaign is about to close on a fault of ours. */
    URGENT
}
