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
 * One message on a ticket — V51, issue #310.
 *
 * <p>Append-only, with no setter of any kind. A support conversation that could be edited
 * after the fact is one nobody can rely on when a refund decision turns on what was
 * promised — and several of them do.
 *
 * <p><strong>{@code internal} is a column and not a separate table</strong>, deliberately
 * against the usual instinct. A note staff leave for each other is read in sequence with
 * the rest of the conversation — that is the whole point of it — so a second table would
 * be joined and interleaved on every read, and the first query that forgot would show a
 * requester what staff said about them. V51 has a {@code CHECK} that only staff may write
 * one.
 */
@Entity
@Table(name = "support_ticket_messages")
public class TicketMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_side", nullable = false, updatable = false)
    private TicketSide authorSide;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "internal", nullable = false, updatable = false)
    private boolean internal;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected TicketMessage() {
        // Hibernate.
    }

    public TicketMessage(UUID ticketId, UUID authorId, TicketSide authorSide, String body, boolean internal) {
        this.id = Identifiers.newIdentifier();
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        this.authorId = Objects.requireNonNull(authorId, "authorId");
        this.authorSide = Objects.requireNonNull(authorSide, "authorSide");
        this.body = Objects.requireNonNull(body, "body");
        this.internal = internal;

        if (internal && authorSide != TicketSide.STAFF) {
            // V51 checks the same thing. Here too, so that the mistake is caught where it
            // is made rather than at the flush — an internal note attributed to the
            // requester is a note the requester would then be shown.
            throw new IllegalArgumentException("Only staff write an internal note");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID ticketId() {
        return ticketId;
    }

    public UUID authorId() {
        return authorId;
    }

    public TicketSide authorSide() {
        return authorSide;
    }

    public String body() {
        return body;
    }

    public boolean internal() {
        return internal;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
