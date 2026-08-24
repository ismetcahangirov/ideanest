package az.ideanest.ticket.infrastructure;

import az.ideanest.ticket.domain.TicketMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V51's messages — issue #310.
 *
 * <p>Oldest first, because a conversation is read forwards. That is the opposite of every
 * other list in the console, and is right for the same reason dispute evidence is: this is
 * an argument rather than a feed.
 *
 * <p>{@link #visibleTo} exists so that no caller has to remember the internal-note filter.
 * The rule is one line and forgetting it once shows a requester what staff said about them,
 * so it is written here rather than in whichever service happens to read the thread.
 */
public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    /** The whole thread, internal notes included. Staff only — the service checks. */
    @Query("SELECT m FROM TicketMessage m WHERE m.ticketId = :ticketId ORDER BY m.createdAt ASC")
    List<TicketMessage> forTicket(@Param("ticketId") UUID ticketId);

    /** The thread as the requester sees it. */
    @Query(
            """
            SELECT m FROM TicketMessage m
            WHERE m.ticketId = :ticketId AND m.internal = false
            ORDER BY m.createdAt ASC
            """)
    List<TicketMessage> visibleTo(@Param("ticketId") UUID ticketId);
}
