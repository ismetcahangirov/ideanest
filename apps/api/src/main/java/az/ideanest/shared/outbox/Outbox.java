package az.ideanest.shared.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * §8.3's transactional outbox (#135): how a module announces something without being
 * able to lie about it.
 *
 * <p><strong>Call this from inside the transaction that makes the change.</strong> That
 * is the entire mechanism. The event becomes a row written by the same transaction as
 * the pledge, the project, or the account it describes, so the two commit together or
 * neither does — and afterwards {@link OutboxRelay} publishes it. What that removes is a
 * pair of failures the platform has today and which every event class in the codebase
 * admits to in a comment:
 *
 * <ul>
 *   <li>Publishing after the commit, as {@code AuthEvents} and {@code ProjectEvents} do
 *       through {@code @TransactionalEventListener}, loses the message when the process
 *       dies in the window between the two. The user is told nothing and nothing knows
 *       it owed them anything.
 *   <li>Publishing before the commit sends a message about something that then rolled
 *       back: a verification link for an account that does not exist, a confirmation for
 *       a pledge nobody made. Unsendable, once sent.
 * </ul>
 *
 * <p>Both come from the same impossibility — a database and a transport cannot be
 * committed to atomically — and no ordering of the two calls makes them one. Writing the
 * intent to the database is what turns two commits into one.
 *
 * <h2>MANDATORY, deliberately</h2>
 *
 * <p>A caller with no transaction of its own has nothing for the event to be atomic
 * with. {@code REQUIRED} would quietly start a transaction here and commit a row on its
 * own, which is precisely the divergence this class exists to prevent — arrived at by the
 * convenience of not having to say so. So it refuses:
 * {@code IllegalTransactionStateException} at the call site, in a test, rather than a
 * guarantee that silently is not one in production.
 *
 * <h2>What a payload should carry</h2>
 *
 * <p>Enough to route on, and no more. A copy of an address or an amount in here is a
 * copy of personal or financial data with a different retention rule from the row it was
 * taken from — {@code ProjectEvents.ProjectLaunched} makes the same argument for
 * carrying an identifier and nothing else. The consumer reads what it needs inside its
 * own transaction.
 */
@Component
public class Outbox {

    private final OutboxEventRepository events;
    private final ObjectMapper json;
    private final Clock clock;

    public Outbox(OutboxEventRepository events, ObjectMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Records an event, in the caller's transaction.
     *
     * <p>Flushed rather than merely queued, so that a payload the database refuses — an
     * empty body, an over-long event type — fails here, at the line that recorded it,
     * instead of at a commit whose stack trace names nothing the author wrote. The row is
     * still invisible to everybody else until that commit; the flush changes when the
     * statement is sent, not when it becomes true.
     *
     * @param aggregateType which kind of thing this happened to: {@code pledge},
     *     {@code project}. Together with the identifier it is the ordering key — events
     *     for one aggregate are dispatched in the order they were recorded, and there is
     *     no order between aggregates
     * @param aggregateId which one
     * @param eventType what happened, in the vocabulary consumers switch on:
     *     {@code pledge.confirmed}
     * @param payload the event body, serialised here with the application's own
     *     {@code ObjectMapper} so that a consumer reads what every other API reader reads
     * @return the event's identifier, which is the identifier a consumer will deduplicate
     *     on. Returned rather than discarded so that a caller can log the two together
     *     and an incident can be traced from a business row to the event it produced
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        OutboxEvent event =
                OutboxEvent.record(aggregateType, aggregateId, eventType, serialise(payload), at);
        return events.saveAndFlush(event).getId();
    }

    private String serialise(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Unchecked in Jackson 3, and still caught for the reason IdempotentRequests
            // gives: this runs inside somebody else's transaction, and a raw Jackson
            // error surfacing from the middle of a pledge confirmation says nothing
            // about which half failed. It must fail — an event nobody can serialise is
            // an event nobody can publish — but it must fail saying so.
            throw new IllegalStateException("An event that cannot be serialised cannot be recorded", e);
        }
    }
}
