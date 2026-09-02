package az.ideanest.shared.outbox;

import az.ideanest.shared.observability.QueueDepthSource;
import org.springframework.stereotype.Component;

/**
 * The outbox's contribution to AD-16's screen — #316.
 *
 * <p>Beside the table it counts, which is the whole point of {@link QueueDepthSource}: the
 * module that owns {@code outbox_events} answers how deep it is, and the health screen
 * never learns that the table exists.
 *
 * <p><strong>The queue that matters most on this screen.</strong> §8.3 routes every
 * cross-module event through it, so a stalled outbox is a platform where pledges are
 * confirmed and nobody is told — which looks, from every other surface, like a platform
 * that is working.
 */
@Component
public class OutboxQueueDepth implements QueueDepthSource {

    private final OutboxEventRepository events;

    public OutboxQueueDepth(OutboxEventRepository events) {
        this.events = events;
    }

    /** An identifier rather than a word, for the reason {@code JobQueueDepth} states — #405. */
    @Override
    public String queueName() {
        return "outbox";
    }

    @Override
    public long waiting() {
        return events.countByState(OutboxEventState.PENDING);
    }

    @Override
    public long dead() {
        return events.countByState(OutboxEventState.DEAD);
    }
}
