package az.ideanest.ticket.application;

import java.util.UUID;

/**
 * No ticket at that identifier — issue #310.
 *
 * <p>404. Nothing to be evasive about: a caller who reaches this endpoint holds
 * {@code HANDLE_SUPPORT}, so confirming a ticket exists tells them nothing the queue would
 * not.
 */
public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(UUID ticketId) {
        super("No ticket " + ticketId);
    }
}
