package az.ideanest.ticket.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import az.ideanest.ticket.domain.SupportTicket;
import az.ideanest.ticket.domain.TicketMessage;
import az.ideanest.ticket.domain.TicketPriority;
import az.ideanest.ticket.domain.TicketSide;
import az.ideanest.ticket.domain.TicketState;
import az.ideanest.ticket.domain.TicketSubjectType;
import az.ideanest.ticket.infrastructure.SupportTicketRepository;
import az.ideanest.ticket.infrastructure.TicketMessageRepository;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support conversations — §4.11's AD-10, issue #310.
 *
 * <h2>Why the console has one at all</h2>
 *
 * <p>#310 was blocked on there being no ticket store, and the obvious alternative to
 * building one is a shared mailbox. V51's header has the argument: §4.11 asks for "tickets
 * with user context and action history", and an email client knows neither. The screen this
 * serves puts the conversation beside the pledge it is about, the account's standing, and
 * every other ticket the same person has raised — which is what makes the fifth complaint
 * from one account readable as a pattern rather than as a fresh request.
 *
 * <h2>Two audiences on one thread</h2>
 *
 * <p>{@link #thread} takes a side. Staff see the internal notes; the requester does not,
 * and the filter is in the repository rather than here so that no future caller has to
 * remember it. Forgetting it once shows somebody what staff said about them.
 *
 * <h2>Reading a ticket is not audited and answering one is</h2>
 *
 * <p>The line the rest of the console draws: {@code ACCOUNTS_SEARCHED} exists because a
 * directory read hands over an email address to somebody with no relationship to the
 * account. A support ticket is a conversation the requester started with the platform, so
 * staff reading it is the thing they asked for. What is recorded is what staff <em>did</em>
 * — answered, reassigned, closed — because that is the "action history" AD-10 names.
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    private static final int PAGE_SIZE = 50;

    private final SupportTicketRepository tickets;
    private final TicketMessageRepository messages;
    private final UserAccounts accounts;
    private final PlatformStaff staff;
    private final AuditLog audit;
    private final Clock clock;

    public SupportTicketService(
            SupportTicketRepository tickets,
            TicketMessageRepository messages,
            UserAccounts accounts,
            PlatformStaff staff,
            AuditLog audit,
            Clock clock) {
        this.tickets = tickets;
        this.messages = messages;
        this.accounts = accounts;
        this.staff = staff;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Raises a ticket on somebody's behalf.
     *
     * <p><strong>Staff-only, and there is no public endpoint behind it.</strong> That is a
     * deliberate limit of this issue rather than an oversight: #310 is the console screen,
     * and a public "contact us" form is a different surface with its own rate limiting,
     * spam handling and anonymous-sender question. What this supports is the real
     * workflow the platform has today — somebody writes in, and a member of staff records
     * the conversation against their account so that it has the context AD-10 is about.
     *
     * @throws UnknownRequesterException when the account named does not exist
     */
    @Transactional
    public TicketFile raise(
            UUID staffId,
            UUID requesterId,
            String subject,
            TicketSubjectType subjectType,
            UUID subjectRef,
            TicketPriority priority,
            String body) {

        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        accounts.findById(requesterId).orElseThrow(() -> new UnknownRequesterException(requesterId));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SupportTicket ticket =
                tickets.save(SupportTicket.raised(requesterId, subject, subjectType, subjectRef, priority, now));

        // The opening message is attributed to the requester, because they are the person
        // who said it — a member of staff transcribing a phone call has not raised a
        // complaint about themselves, and attributing it to staff would make every
        // recorded call look like the platform contacting somebody.
        messages.save(new TicketMessage(ticket.id(), requesterId, TicketSide.REQUESTER, body, false));

        record(staffId, ticket.id(), "raised; requester=%s; priority=%s".formatted(requesterId, priority));
        log.info("Ticket {} raised for {} by {}", ticket.id(), requesterId, staffId);

        return new TicketFile(ticket, messages.forTicket(ticket.id()));
    }

    /** AD-10's queue: everything still somebody's work, most urgent first. */
    @Transactional(readOnly = true)
    public List<SupportTicket> queue(UUID staffId, int page) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        return tickets.queue(PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /** Everything, newest first, optionally narrowed to one state. */
    @Transactional(readOnly = true)
    public List<SupportTicket> list(UUID staffId, TicketState state, int page) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        PageRequest request = PageRequest.of(Math.max(page, 0), PAGE_SIZE);

        return state == null ? tickets.page(request) : tickets.pageByState(state, request);
    }

    /**
     * One ticket, its thread, and the rest of this person's history.
     *
     * <p>The history is AD-10's "user context" and is why this returns three things rather
     * than one. A ticket read on its own is a request; the same ticket read beside four
     * earlier ones from the same account is a pattern, and the console should not make
     * somebody go and look for that.
     */
    @Transactional(readOnly = true)
    public TicketContext inspect(UUID staffId, UUID ticketId) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));

        return new TicketContext(
                new TicketFile(ticket, messages.forTicket(ticketId)),
                tickets.forRequester(ticket.requesterId()));
    }

    /**
     * The thread, as one side or the other sees it.
     *
     * @param side {@code STAFF} includes the internal notes. The filter is
     *     {@code TicketMessageRepository}'s, so that it cannot be forgotten here
     */
    @Transactional(readOnly = true)
    public List<TicketMessage> thread(UUID staffId, UUID ticketId, TicketSide side) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        return side == TicketSide.STAFF ? messages.forTicket(ticketId) : messages.visibleTo(ticketId);
    }

    /**
     * Answers a ticket, or leaves a note on it.
     *
     * <p>The state follows the message rather than being set separately —
     * {@code SupportTicket.answered} has the argument: a reply that did not move the
     * ticket off the queue is a reply the next person has to notice by reading it.
     *
     * <p><strong>An internal note does not move the ticket</strong>, which is the one case
     * that is not obvious. A note is staff talking to staff, so the requester is still
     * waiting and the ticket stays where it is.
     */
    @Transactional
    public TicketFile reply(UUID staffId, UUID ticketId, String body, boolean internal) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        messages.save(new TicketMessage(ticketId, staffId, TicketSide.STAFF, body, internal));

        if (!internal) {
            ticket.answered(TicketSide.STAFF, now);
        }

        record(staffId, ticketId, internal ? "note added" : "answered");
        return new TicketFile(ticket, messages.forTicket(ticketId));
    }

    /** Picks a ticket up, hands it on, or puts it back in the queue with a null assignee. */
    @Transactional
    public SupportTicket assign(UUID staffId, UUID ticketId, UUID assigneeId) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));

        if (assigneeId != null) {
            accounts.findById(assigneeId).orElseThrow(() -> new UnknownRequesterException(assigneeId));
        }
        ticket.assign(assigneeId, clock.instant().truncatedTo(ChronoUnit.MICROS));

        record(staffId, ticketId, "assigned=" + assigneeId);
        return ticket;
    }

    /** Changes how urgent it is. Staff-set — see {@code TicketPriority}. */
    @Transactional
    public SupportTicket prioritise(UUID staffId, UUID ticketId, TicketPriority priority) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));

        ticket.prioritise(priority, clock.instant().truncatedTo(ChronoUnit.MICROS));

        record(staffId, ticketId, "priority=" + priority);
        return ticket;
    }

    /** Moves the ticket. The resolution date follows — {@code SupportTicket.moveTo} sees to it. */
    @Transactional
    public SupportTicket moveTo(UUID staffId, UUID ticketId, TicketState state) {
        staff.requireCapability(staffId, StaffCapability.HANDLE_SUPPORT);
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));

        ticket.moveTo(state, clock.instant().truncatedTo(ChronoUnit.MICROS));

        record(staffId, ticketId, "state=" + state);
        log.info("Ticket {} moved to {} by {}", ticketId, state, staffId);
        return ticket;
    }

    private void record(UUID staffId, UUID ticketId, String detail) {
        audit.record(
                AuditAction.SUPPORT_TICKET_HANDLED,
                ticketId,
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                detail);
    }

    /** A ticket and its thread. */
    public record TicketFile(SupportTicket ticket, List<TicketMessage> messages) {
    }

    /**
     * A ticket, its thread, and everything else this person has asked.
     *
     * @param history includes the ticket itself. Filtering it out here would mean the
     *     console had to know that, and a list of "their other tickets" that silently
     *     excludes one is a list somebody will eventually count
     */
    public record TicketContext(TicketFile file, List<SupportTicket> history) {
    }
}
