package az.ideanest.shared.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One event, as the dispatch target sees it.
 *
 * <p>A record rather than the entity, so that a dispatcher — and, later, a broker
 * adapter — cannot reach the row, change its state, or hold a managed instance past
 * the transaction that loaded it. What a transport needs is the identity, the routing,
 * and the bytes.
 *
 * <p><strong>Handlers must tolerate redelivery.</strong> That is not a caveat, it is
 * the contract. The relay dispatches and then commits the fact that it dispatched, so
 * a crash, a timeout, or a rollback between those two republishes the same event — and
 * it has to, because the alternative is deciding an event was delivered on the
 * strength of a call whose outcome nobody recorded. {@link #id()} is stable across
 * every delivery of the same event, so a handler whose effect is not naturally
 * idempotent deduplicates on it: remember the identifier with the effect, in the same
 * transaction as the effect, and ignore an identifier already seen.
 *
 * @param id the event's stable identifier, the same on every redelivery. The key a
 *     consumer deduplicates on
 * @param aggregateType which kind of thing this happened to — {@code pledge},
 *     {@code project}. Ordering is guaranteed within one aggregate and nowhere else
 * @param aggregateId which one
 * @param eventType what happened, in the consumers' vocabulary:
 *     {@code pledge.confirmed}
 * @param payload the serialised event, verbatim — the bytes the business transaction
 *     committed, not a re-serialisation of them
 * @param occurredAt when the event was recorded, which is when its transaction wrote
 *     it. Not when it was dispatched: a consumer reasoning about order or staleness
 *     needs the former, and the latter tells it only how long the transport was down
 * @param deliveryAttempt which delivery this is, counted from one. A handler that
 *     sees a number above one is being retried, which is worth a log line and is
 *     never in itself an error
 */
public record OutboxMessage(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        int deliveryAttempt) {

    @Override
    public String toString() {
        // No payload. An event body carries whatever the business write carried, and
        // this record ends up in log lines about failed dispatches.
        return "OutboxMessage[id=" + id + ", eventType=" + eventType + ", aggregateType=" + aggregateType
                + ", aggregateId=" + aggregateId + ", deliveryAttempt=" + deliveryAttempt + "]";
    }
}
