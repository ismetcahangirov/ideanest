package az.ideanest.payment.application;

import az.ideanest.payment.domain.PaymentEvent;
import az.ideanest.payment.domain.PaymentEventType;
import java.util.Optional;
import java.util.Set;

/**
 * What acts on a verified provider event (#66).
 *
 * <p><strong>Nothing implements this yet, and the issue is still finished.</strong> #66
 * is "verify signatures, reject replays, and process each event exactly once", and all
 * three of those are the ingestion's: {@code PaymentProvider#parseWebhook} verifies and
 * checks the timestamp, V43's unique index makes the processing exactly-once, and
 * {@code ProviderWebhooks} is the transaction that binds a delivery to its effect. What
 * an event <em>means</em> belongs to the issues that own the outcome — a settled refund
 * is #67's, a chargeback is #68's, a completed payout is #69's — and implementing them
 * here would be three features written behind the endpoint that receives them rather
 * than in the modules that own their state.
 *
 * <p>So today every delivery is verified, recorded, and marked {@code IGNORED}. That is
 * not a stub: an event with no handler genuinely has nothing to do about it, and V43's
 * {@code IGNORED} exists precisely because most of what a provider emits is like that
 * even once every handler is written.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <ul>
 *   <li><strong>It runs inside the delivery's transaction</strong>, and the row that
 *       records the delivery commits with it. Throwing rolls both back and the provider
 *       retries; returning normally means the effect and the deduplication row are one
 *       commit. A handler that catches its own failure and returns has told the provider
 *       the event was handled.
 *   <li><strong>It does not need to be idempotent</strong>, which is unusual enough to be
 *       worth saying. The unique index means it is called at most once per event, ever.
 *   <li><strong>It does the smallest durable thing.</strong> Anything slow — a
 *       notification, a fan-out — is an outbox event, because the handler's latency is
 *       the provider's HTTP timeout.
 * </ul>
 */
public interface PaymentEventHandler {

    /**
     * Which events this handler wants.
     *
     * <p>A set rather than one type, because #68's chargeback handling wants
     * {@code CHARGEBACK_OPENED}, {@code CHARGEBACK_WON} and {@code CHARGEBACK_LOST} and
     * they are one piece of logic with three entry points.
     */
    Set<PaymentEventType> handles();

    /**
     * Acts on the event.
     *
     * @return one line saying what was done, for {@code provider_webhook_events.outcome}
     *     and for the support conversation. Empty when the handler recognised the event
     *     and decided it needed nothing — which is still {@code PROCESSED}, because a
     *     handler looked at it
     */
    Optional<String> handle(PaymentEvent event);
}
