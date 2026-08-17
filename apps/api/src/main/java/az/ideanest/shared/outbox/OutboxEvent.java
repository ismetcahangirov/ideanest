package az.ideanest.shared.outbox;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One recorded event, and how far it has got.
 *
 * <p><strong>The row is the guarantee.</strong> It is written by the same transaction
 * as the business change it describes, so the two commit together or neither does —
 * which is the whole of #135. Everything else here is about getting it out afterwards:
 * a state, a count of deliveries attempted, and the instant the next one may happen.
 *
 * <p>The state machine is two edges and no way back. {@code PENDING -> PUBLISHED} when
 * the dispatch target accepted the message; {@code PENDING -> DEAD} when the attempts
 * ran out. There is no {@code cancelled}, because an event is a statement about
 * something that already happened and cannot be made untrue by deciding not to send
 * it — that distinction is also why this is not the job queue (#134).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The queue position, assigned by the database.
     *
     * <p>Never written from here — V19 declares it {@code GENERATED ALWAYS}, so a
     * caller cannot choose its own place in the queue — and read back after the insert
     * for the same reason {@code created_at} is everywhere else in this schema.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "sequence_no", nullable = false, insertable = false, updatable = false)
    private long sequenceNo;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    /** The serialised event, verbatim. Immutable: an event is not edited after the fact. */
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private OutboxEventState state;

    /** Deliveries attempted, successful or not. The number the retry bound is against. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Generated(event = EventType.INSERT)
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA.
    }

    /**
     * A new event, eligible for dispatch the moment its transaction commits.
     *
     * <p>The instant is passed in rather than read from a clock here, for the reason
     * V17 gives: the retry schedule is a rule about time, and a test has to be able to
     * ask what happens ten minutes later without waiting ten minutes.
     */
    public static OutboxEvent record(
            String aggregateType, UUID aggregateId, String eventType, String payload, Instant at) {

        OutboxEvent event = new OutboxEvent();
        event.id = Identifiers.newIdentifier();
        event.aggregateType = requireText(aggregateType, "An event happened to a kind of thing");
        event.aggregateId = Objects.requireNonNull(aggregateId, "An event happened to something");
        event.eventType = requireText(eventType, "An event has a type");
        event.payload = requireText(payload, "An event has a body");
        event.state = OutboxEventState.PENDING;
        event.attempts = 0;
        // Eligible immediately: there is nothing to wait for until something has failed.
        event.nextAttemptAt = Objects.requireNonNull(at, "An event is recorded at a time");
        return event;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /** What the dispatch target is handed, including which delivery this is. */
    public OutboxMessage asMessage() {
        return new OutboxMessage(
                id, aggregateType, aggregateId, eventType, payload, occurredAt, attempts + 1);
    }

    /**
     * The target accepted the message.
     *
     * <p>{@code last_error} is deliberately left alone. "Published, after five failures
     * that all said connection refused" is the sentence that turns a resolved incident
     * into an explanation, and clearing it would leave nothing but a published row that
     * looks as though it always went first time.
     */
    public void published(Instant at) {
        this.attempts += 1;
        this.state = OutboxEventState.PUBLISHED;
        this.publishedAt = Objects.requireNonNull(at, "A publication happened at a time");
    }

    /**
     * The attempt failed and there are attempts left.
     *
     * <p>Stays {@code PENDING}, which is what makes delivery at-least-once rather than
     * best-effort: the row is still the platform's outstanding promise, and the only
     * thing that changed is when it may be tried again.
     */
    public void retryAfter(Instant at, Duration backoff, String error) {
        this.attempts += 1;
        this.lastError = requireText(error, "A failure has a reason");
        this.nextAttemptAt = Objects.requireNonNull(at, "A failure happened at a time").plus(backoff);
    }

    /**
     * The attempts ran out.
     *
     * <p>Terminal, and it carries the reason — V19's
     * {@code outbox_events_dead_letters_say_why} refuses a dead letter without one,
     * because an event abandoned for a reason nobody recorded is a disappearance rather
     * than a decision.
     */
    public void deadLetter(Instant at, String error) {
        this.attempts += 1;
        this.lastError = requireText(error, "A dead letter says why it died");
        this.state = OutboxEventState.DEAD;
        // Left where it is rather than nulled: the column is NOT NULL, and the last
        // instant this was eligible is a true statement about the row.
        this.nextAttemptAt = Objects.requireNonNull(at, "A dead letter died at a time");
    }

    public UUID getId() {
        return id;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxEventState getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof OutboxEvent event && Objects.equals(id, event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No payload: an event body carries whatever the business write carried, and
        // this ends up in log lines about dispatch failures.
        return "OutboxEvent[id=" + id + ", eventType=" + eventType + ", state=" + state + ", attempts=" + attempts
                + "]";
    }
}
