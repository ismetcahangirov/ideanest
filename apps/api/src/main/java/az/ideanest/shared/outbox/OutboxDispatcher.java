package az.ideanest.shared.outbox;

/**
 * Where a published event goes. The seam, and deliberately nothing more.
 *
 * <p><strong>#135 is the guarantee, not the transport.</strong> The hard part of an
 * outbox is that the event and the business write cannot diverge, and that part is
 * finished once the row exists and something drains it. Which wire the message leaves on
 * is a separate decision with separate operational consequences — a broker to run, a
 * schema registry, a delivery contract with consumers who do not share our database —
 * and building it now would have meant choosing it before anything needed it.
 *
 * <p>So this interface is one method, and the only implementation today is
 * {@link ApplicationEventOutboxDispatcher}, which republishes in process. When a broker
 * arrives it implements this, and neither {@link OutboxRelay}, nor {@link Outbox}, nor
 * V19's table changes.
 *
 * <h2>The contract, from the relay's side</h2>
 *
 * <p><strong>Return normally only when the transport has accepted the message.</strong>
 * The relay marks the row {@code PUBLISHED} on a normal return, and that is the last
 * moment anything will look at it: an implementation that swallows its own failures turns
 * at-least-once delivery into a table full of events that say they were sent.
 *
 * <p><strong>Throw to be retried.</strong> Any {@code RuntimeException} leaves the row
 * pending with a later attempt time, up to the configured bound, after which it is a dead
 * letter carrying the message of the last failure.
 *
 * <p><strong>Do not write to the database here, and do not mark the transaction
 * rollback-only.</strong> This is called inside the relay's own transaction — the one
 * holding the row's lock — so a nested {@code @Transactional} bean that fails and marks
 * that transaction rollback-only takes the failure record down with it: the commit is
 * refused, the row stays exactly as it was, and the attempt is never counted. Delivery is
 * still safe, because an unchanged row is retried, but the backoff and the dead letter
 * stop working, and an event that can never be delivered is then retried for ever. A
 * transport publishes; it does not persist.
 */
@FunctionalInterface
public interface OutboxDispatcher {

    /**
     * Publishes one event.
     *
     * @param message the event, including which delivery attempt this is. The same
     *     message may arrive more than once — see {@link OutboxMessage} for why, and for
     *     what a handler is expected to do about it
     * @throws RuntimeException when the transport did not accept it, which leaves the
     *     event pending for another attempt
     */
    void dispatch(OutboxMessage message);
}
