package az.ideanest.ticket.infrastructure;

import az.ideanest.ticket.domain.SupportTicket;
import az.ideanest.ticket.domain.TicketState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V51's tickets — issue #310.
 *
 * <p>The queue is ordered by priority and then by age. Both, and in that order: age alone
 * makes an urgent ticket wait behind a week of questions, and priority alone lets a normal
 * one sit for a month because there is always another normal one.
 */
public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /**
     * The queue: everything still somebody's work, most urgent first.
     *
     * <p>The priority sort is by the enum's ordinal, which is why {@code TicketPriority} is
     * declared from {@code LOW} to {@code URGENT}. A sort by name would put {@code HIGH}
     * above {@code URGENT}.
     */
    @Query(
            """
            SELECT t FROM SupportTicket t
            WHERE t.state IN (
                az.ideanest.ticket.domain.TicketState.OPEN,
                az.ideanest.ticket.domain.TicketState.PENDING)
            ORDER BY t.priority DESC, t.createdAt ASC
            """)
    List<SupportTicket> queue(Pageable pageable);

    /** Everything, newest first. */
    @Query("SELECT t FROM SupportTicket t ORDER BY t.createdAt DESC")
    List<SupportTicket> page(Pageable pageable);

    /** The same, narrowed to one state. Two queries rather than a nullable parameter. */
    @Query("SELECT t FROM SupportTicket t WHERE t.state = :state ORDER BY t.createdAt DESC")
    List<SupportTicket> pageByState(@Param("state") TicketState state, Pageable pageable);

    /**
     * What this person has asked us before.
     *
     * <p>§4.11's "user context": the ticket in front of somebody is read alongside every
     * other one the same account has raised, because the fifth complaint from one person is
     * a different conversation from the first.
     */
    @Query("SELECT t FROM SupportTicket t WHERE t.requesterId = :requesterId ORDER BY t.createdAt DESC")
    List<SupportTicket> forRequester(@Param("requesterId") UUID requesterId);

    /** One person's workload, for the console's own list. */
    @Query(
            """
            SELECT t FROM SupportTicket t
            WHERE t.assigneeId = :assigneeId
            ORDER BY t.priority DESC, t.createdAt ASC
            """)
    List<SupportTicket> assignedTo(@Param("assigneeId") UUID assigneeId);
}
