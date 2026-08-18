package az.ideanest.audit;

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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * One privileged action, as it was recorded.
 *
 * <p><strong>There is no method here that changes anything.</strong> No setters, no
 * state machine, no second constructor — the object has one way in and none back.
 * That is not the guarantee, though; V21's trigger is. This is the half of it that
 * makes an accidental change fail at compile time rather than at the statement, and
 * every column is {@code updatable = false} so that a dirty-checked flush cannot
 * emit an UPDATE the database would then refuse — which would turn an audited
 * action into a 500 for the user who performed it.
 *
 * <p>The instant is the database's, unlike every other {@code Instant} in this
 * service, which is passed in from an injected {@link java.time.Clock} so that a
 * test can move time. Nothing here is a rule about time — the row records when
 * something happened and drives nothing — and on this table specifically, an
 * instant no caller supplied is an instant no caller can backdate.
 */
@Entity
@Table(name = "audit_logs")
public class AuditEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private AuditActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    /** Null on every row this release writes. See {@link AuditActor} for why it exists. */
    @Column(name = "on_behalf_of_id", updatable = false)
    private UUID onBehalfOfId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private AuditOutcome outcome;

    /**
     * {@code inet}, which validates the value and normalises IPv6 — the same
     * decision, and the same mapping, as {@code sessions.ip_address}.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "source_address", updatable = false)
    private String sourceAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "detail", updatable = false)
    private String detail;

    protected AuditEntry() {
        // JPA.
    }

    /**
     * A record of something that has just been done.
     *
     * <p>Package private: {@link AuditLog} is the only way in, because it is where
     * the redaction and the length bounds are applied. A second construction path
     * would be a way of writing an unredacted row that passed review by looking
     * like the first one.
     *
     * @param environment where it was done from, taken from the request rather than
     *     supplied by the caller
     * @param detail already redacted and already truncated
     */
    static AuditEntry record(
            AuditAction action,
            UUID entityId,
            AuditActor actor,
            AuditOutcome outcome,
            AuditEnvironment environment,
            String detail) {

        AuditEntry entry = new AuditEntry();
        entry.id = Identifiers.newIdentifier();
        entry.action = Objects.requireNonNull(action, "An audit row says what was done").action();
        entry.entityType = action.entityType();
        // Not nullable, and refused here as well as by V21: a row that does not say
        // what the action was done to cannot be found by anybody looking for it.
        entry.entityId = Objects.requireNonNull(entityId, "An audit row says what it was done to");
        Objects.requireNonNull(actor, "An audit row says who did it");
        entry.actorType = actor.type();
        entry.actorId = actor.id();
        entry.onBehalfOfId = actor.onBehalfOf();
        entry.outcome = Objects.requireNonNull(outcome, "An audit row says how it came out");

        AuditEnvironment where = environment == null ? AuditEnvironment.NONE : environment;
        entry.sourceAddress = where.sourceAddress();
        entry.userAgent = where.userAgent();
        entry.requestId = where.requestId();
        entry.traceId = where.traceId();
        entry.detail = detail;
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getOnBehalfOfId() {
        return onBehalfOfId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AuditEntry entry && Objects.equals(id, entry.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No detail, no user agent, no address. This ends up in log lines, and
        // §17.4's rules about a log line are not relaxed by the text having come
        // from the audit table.
        return "AuditEntry[id=" + id + ", action=" + action + ", entityId=" + entityId + ", outcome=" + outcome + "]";
    }
}
