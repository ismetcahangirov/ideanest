package az.ideanest.payment.domain;

/**
 * What became of a verified provider delivery.
 *
 * <p><strong>Two values, and the two that are missing are the interesting part.</strong>
 *
 * <p>There is no {@code PENDING}. The row and the effect it caused are one commit — see
 * V43 — so a committed row is by construction a delivery that has been dealt with. A
 * row written before its handler ran would make the provider's next redelivery look
 * like a duplicate of work that never happened, which is the one failure mode a
 * deduplication table must not have.
 *
 * <p>There is no {@code FAILED} either. A handler that throws takes its transaction
 * with it, so there is no row at all, the response is a 500, and the provider sends the
 * delivery again — which is the whole reason the platform does not need a retry of its
 * own here. Every provider in §9.3 retries a delivery it did not get a 2xx for, and
 * R-07 is on the list partly so that this is true of whichever one is chosen.
 */
public enum WebhookEventState {

    /** A handler recognised the event and did something about it. */
    PROCESSED,

    /**
     * Verified, recorded, and nothing to do about it.
     *
     * <p><strong>Not an error and not a gap.</strong> A provider emits every event type
     * it has, most of which describe products the platform does not use, and answering
     * 200 while doing nothing is the correct handling of those. Recording them is what
     * makes "the provider says it sent us the dispute notification" a question with an
     * answer.
     */
    IGNORED
}
