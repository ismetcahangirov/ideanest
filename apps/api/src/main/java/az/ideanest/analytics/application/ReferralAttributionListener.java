package az.ideanest.analytics.application;

import az.ideanest.shared.outbox.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * How the analytics module hears that a pledge was confirmed.
 *
 * <p>A listener on {@code OutboxMessage}, switching on {@link OutboxMessage#eventType()},
 * because that is the shape {@code ApplicationEventOutboxDispatcher} prescribes in its
 * own class comment: the dispatcher stays untyped so that no per-event Java class
 * becomes a compile-time coupling between the relay and every module's events, and so
 * that nothing has to be rebuilt the day these messages arrive from another service
 * instead of from a table.
 *
 * <p><strong>This is the whole of the coupling between analytics and the pledge
 * module, and it is a string.</strong> {@code ModuleBoundaryTests} forbids reaching
 * into another module's domain or infrastructure; a listener on a published event
 * reaches into neither. Nothing here imports anything from {@code az.ideanest.pledge},
 * which is checkable and is checked.
 *
 * <p><strong>Nothing publishes this event yet.</strong>
 * {@link PledgeConfirmed} says so at length, including what the remaining work is and
 * why it is not in this change. Until it lands, this listener sees no traffic and the
 * attribution table stays empty.
 *
 * <h2>Failure</h2>
 *
 * <p>Two kinds, and they are answered differently on purpose.
 *
 * <ul>
 *   <li><strong>The attribution failed to write.</strong> It throws, the dispatch
 *       transaction rolls back with it, the event stays {@code PENDING}, and the relay
 *       tries again. That is what an at-least-once transport is for and it is the
 *       reason the write is {@code MANDATORY}.
 *   <li><strong>The payload cannot be read.</strong> It throws too, and that is a
 *       deliberate choice rather than an oversight. An event whose body this module
 *       does not understand will not be understood on the next attempt either, so the
 *       retries are wasted and the eighth one dead-letters it — which is exactly
 *       right: a producer and a consumer that disagree about a payload is a fault
 *       somebody has to see, and swallowing it would mean pledges silently going
 *       unattributed with nothing anywhere saying so.
 * </ul>
 */
@Component
public class ReferralAttributionListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralAttributionListener.class);

    private final ReferralAttributionService attribution;
    private final ObjectMapper json;

    public ReferralAttributionListener(ReferralAttributionService attribution, ObjectMapper json) {
        this.attribution = attribution;
        this.json = json;
    }

    /**
     * Attributes the pledge this event describes, and ignores every other event.
     *
     * <p>Synchronous, and it has to be: {@code ApplicationEventOutboxDispatcher}'s
     * comment states that an {@code @Async} listener would make every event look
     * delivered the instant it was handed over, which is the one behaviour the
     * dispatcher asks an implementation not to have. It also runs inside the dispatch
     * transaction, which is what {@code ReferralAttributionService}'s
     * {@code MANDATORY} propagation depends on.
     */
    @EventListener
    public void on(OutboxMessage message) {
        if (!PledgeConfirmed.EVENT_TYPE.equals(message.eventType())) {
            return;
        }
        if (message.deliveryAttempt() > 1) {
            // Never in itself an error — OutboxMessage says so — and worth a line,
            // because a redelivery that keeps happening is the shape of a poisoned
            // event and this is where it would be visible.
            log.info("Attributing {} on delivery attempt {}", message, message.deliveryAttempt());
        }
        attribution.attribute(message.id(), read(message));
    }

    /**
     * The payload, as this module's contract for it.
     *
     * <p>Read with the application's own {@code ObjectMapper}, so that the money in it
     * is subject to §10.3's rules — an amount as a string, never a JSON number —
     * without this class knowing that is what it is asking for.
     */
    private PledgeConfirmed read(OutboxMessage message) {
        try {
            return json.readValue(message.payload(), PledgeConfirmed.class);
        } catch (JacksonException malformed) {
            // Unchecked in Jackson 3, and still caught, for Outbox's reason: a raw
            // databind error surfacing from the middle of a dispatch says nothing
            // about which event it was about. It must fail — see the class comment for
            // why swallowing it would be worse — but it must fail saying so.
            throw new IllegalStateException("A " + PledgeConfirmed.EVENT_TYPE + " event " + message.id()
                    + " could not be read as one", malformed);
        }
    }
}
