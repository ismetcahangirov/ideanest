package az.ideanest.shared.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The transport, until there is one: republishes the event in this process.
 *
 * <p><strong>This is not a placeholder that does nothing.</strong> It is what makes the
 * outbox useful before a broker exists, because the platform's consumers are all in this
 * process today — the notification listeners, the reminder sweep, the search index feed of
 * §11.4. Routed through here they gain the guarantee they do not have when a module
 * publishes to Spring's event bus directly: the event is a committed row first, so a crash
 * before the listener runs costs a second of latency instead of the message.
 *
 * <p>An in-process listener is also where the redelivery contract starts to bite rather
 * than where it is excused from it. A listener that ran twice because the relay's
 * transaction rolled back after dispatch is exactly the case {@link OutboxMessage}
 * describes, and the answer is the same as it will be for a broker consumer: be
 * idempotent, keyed on the event's identifier.
 *
 * <p>The listener side stays deliberately untyped — a listener asks for
 * {@link OutboxMessage} and switches on {@link OutboxMessage#eventType()} — because a
 * per-event Java type published from here would be a compile-time coupling between the
 * relay and every module's event classes, and it would have to be rebuilt the day these
 * messages start arriving from another service instead.
 */
@Component
public class ApplicationEventOutboxDispatcher implements OutboxDispatcher {

    private final ApplicationEventPublisher events;

    public ApplicationEventOutboxDispatcher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronous, and that is the contract rather than an oversight: Spring's
     * publisher calls the listeners on this thread, so a listener that throws is a
     * dispatch that failed and the event is retried. An {@code @Async} listener would
     * make every event look delivered the instant it was handed over, which is the one
     * behaviour {@link OutboxDispatcher} asks an implementation not to have.
     */
    @Override
    public void dispatch(OutboxMessage message) {
        events.publishEvent(message);
    }
}
