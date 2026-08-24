package az.ideanest.ticket.api;

import az.ideanest.ticket.application.SupportTicketService;
import az.ideanest.ticket.domain.SupportTicket;
import az.ideanest.ticket.domain.TicketMessage;
import az.ideanest.ticket.domain.TicketPriority;
import az.ideanest.ticket.domain.TicketSide;
import az.ideanest.ticket.domain.TicketState;
import az.ideanest.ticket.domain.TicketSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AD-10's screen, as the service describes it — issue #310.
 */
public final class TicketResponses {

    private TicketResponses() {
    }

    /**
     * One ticket, without its thread.
     *
     * <p>The requester is an identifier and not a name. Turning one into a person is
     * {@code GET /v1/admin/users/{id}}, which is audited because it hands over an email
     * address — so this endpoint does not quietly do the same for every row of a queue and
     * leave one audit entry saying "listed tickets".
     */
    public record Ticket(
            UUID id,
            UUID requesterId,
            String subject,
            TicketSubjectType subjectType,
            UUID subjectRef,
            TicketState state,
            TicketPriority priority,
            UUID assigneeId,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt) {

        public static Ticket of(SupportTicket ticket) {
            return new Ticket(
                    ticket.id(),
                    ticket.requesterId(),
                    ticket.subject(),
                    ticket.subjectType(),
                    ticket.subjectRef(),
                    ticket.state(),
                    ticket.priority(),
                    ticket.assigneeId(),
                    ticket.createdAt(),
                    ticket.updatedAt(),
                    ticket.resolvedAt());
        }
    }

    /** One message. */
    public record Message(
            UUID id, UUID authorId, TicketSide authorSide, String body, boolean internal, Instant createdAt) {

        public static Message of(TicketMessage message) {
            return new Message(
                    message.id(),
                    message.authorId(),
                    message.authorSide(),
                    message.body(),
                    message.internal(),
                    message.createdAt());
        }
    }

    /** A thread on its own, for the requester-view endpoint. */
    public record Thread(List<Message> messages) {

        public static Thread of(List<TicketMessage> messages) {
            return new Thread(messages.stream().map(Message::of).toList());
        }
    }

    /** A ticket and its thread. */
    public record TicketFile(Ticket ticket, List<Message> messages) {

        public static TicketFile of(SupportTicketService.TicketFile file) {
            return new TicketFile(
                    Ticket.of(file.ticket()), file.messages().stream().map(Message::of).toList());
        }
    }

    /**
     * A ticket, its thread, and everything else this person has asked.
     *
     * @param history includes the ticket itself, and the service says why: a list of
     *     "their other tickets" that silently excludes one is a list somebody will
     *     eventually count
     */
    public record TicketContext(TicketFile file, List<Ticket> history) {

        public static TicketContext of(SupportTicketService.TicketContext context) {
            return new TicketContext(
                    TicketFile.of(context.file()),
                    context.history().stream().map(Ticket::of).toList());
        }
    }

    /** A page of tickets. */
    public record TicketPage(List<Ticket> tickets, int page, boolean hasMore) {

        public static TicketPage of(List<SupportTicket> tickets, int page, int size) {
            return new TicketPage(tickets.stream().map(Ticket::of).toList(), page, tickets.size() == size);
        }
    }
}
