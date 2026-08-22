package az.ideanest.pledge.application;

import az.ideanest.shared.outbox.OutboxMessage;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Ends every pledge on a campaign that has been stopped — #103.
 *
 * <p>A listener on {@code OutboxMessage} switching on {@link OutboxMessage#eventType()},
 * exactly as {@code ReferralAttributionListener} is, and it is a listener rather than a
 * call for the reason {@code CampaignHaltedEvent} gives: the project module may not read
 * {@code pledges}, and it cannot call this module either, because this module already
 * depends on it through {@code PledgeAcceptance} and {@code ModuleBoundaryTests} refuses
 * a cycle.
 *
 * <p><strong>Both event types, and the work is identical.</strong> A campaign the creator
 * cancelled and one trust and safety suspended both stop taking money and both give every
 * held place back. What differs is what backers are told, and that is a message rather
 * than a state change.
 *
 * <p><strong>Redelivery is ordinary.</strong> {@code OutboxMessage} states at-least-once
 * as a contract rather than an edge case, and this is idempotent by construction: the
 * second pass finds no active pledges on the campaign and cancels nothing.
 */
@Component
public class CampaignHaltedListener {

    private static final Logger log = LoggerFactory.getLogger(CampaignHaltedListener.class);

    /**
     * The two names a halt is announced under.
     *
     * <p>Declared here rather than imported from {@code CampaignHaltedEvent}, following
     * {@code NotificationEvents} and {@code ReferralAttributionListener}: the event's
     * wire format is the contract, this module reads its own copy of it, and neither
     * side imports the other's record. What that costs is a string that has to match;
     * what it buys is a consumer that goes on working when these messages start arriving
     * from another service rather than from a bean in this process.
     */
    private static final Set<String> HALTS = Set.of("project.canceled", "project.suspended");

    private final PledgeCancellationService cancellations;
    private final ObjectMapper json;

    public CampaignHaltedListener(PledgeCancellationService cancellations, ObjectMapper json) {
        this.cancellations = cancellations;
        this.json = json;
    }

    /**
     * Cancels the campaign's pledges, and ignores every other event.
     *
     * <p>Synchronous, like every other outbox listener here: {@code
     * ApplicationEventOutboxDispatcher} states that an {@code @Async} listener would make
     * every event look delivered the instant it was handed over.
     */
    @EventListener
    public void on(OutboxMessage message) {
        if (!HALTS.contains(message.eventType())) {
            return;
        }
        if (message.deliveryAttempt() > 1) {
            // Never in itself an error — OutboxMessage says so — and worth a line,
            // because a redelivery that keeps happening is the shape of a poisoned
            // event and this is where it would be visible.
            log.info("Releasing the pledges of {} on delivery attempt {}", message, message.deliveryAttempt());
        }
        cancellations.releaseCampaign(projectOf(message), message.eventType());
    }

    /**
     * Which campaign stopped.
     *
     * <p>Read from the payload rather than from {@code aggregateId}, although the two are
     * the same identifier today: the payload is the contract the producer documents, and
     * an aggregate identifier is §8.3's routing key. A consumer reading the routing key
     * as data is one that breaks the day a campaign's events are keyed by something else.
     */
    private UUID projectOf(OutboxMessage message) {
        try {
            return UUID.fromString(json.readTree(message.payload())
                    .required("projectId")
                    .asString());
        } catch (JacksonException | IllegalArgumentException malformed) {
            // Unchecked in Jackson 3, and still caught, for Outbox's reason: a raw
            // databind error surfacing from the middle of a dispatch says nothing about
            // which event it was about. It must fail — a halt whose pledges were not
            // released is a campaign that has stopped and is still holding stock — but
            // it must fail saying so.
            throw new IllegalStateException(
                    "A " + message.eventType() + " event " + message.id() + " named no campaign", malformed);
        }
    }
}
