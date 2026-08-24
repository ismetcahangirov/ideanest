package az.ideanest.ticket.domain;

/**
 * What a ticket is about - V51, issue #310.
 *
 * <p>§4.11 asks for "tickets with user context", and this is what makes the context
 * possible: the console screen puts the conversation beside the thing it concerns, which
 * needs the thing to be a column rather than a sentence somebody pasted.
 *
 * <p>{@link #NONE} exists because plenty of tickets are about nothing in particular -
 * "how do I change my address" - and a taxonomy with no escape hatch gets one anyway,
 * spelled as whichever value is nearest.
 */
public enum TicketSubjectType {

    NONE,

    PROJECT,

    PLEDGE,

    /**
     * Somebody else's account.
     *
     * <p>Distinct from the requester, who is always on the ticket. This is the ticket
     * about another person - a complaint, or a request about somebody a creator is
     * working with.
     */
    ACCOUNT;

    /** Whether a ticket of this kind names something. False only for {@link #NONE}. */
    public boolean needsReference() {
        return this != NONE;
    }
}
