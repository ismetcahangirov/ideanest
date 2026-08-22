package az.ideanest.payment.domain;

/**
 * A delivery that failed §17.2's checks: the signature did not verify, the timestamp
 * was outside the tolerance, or the body could not be read at all.
 *
 * <p>Thrown by {@link PaymentProvider#parseWebhook}, which is the whole reason
 * verification and parsing are one call: a caller cannot obtain a
 * {@link PaymentEvent} without having verified it, because the only thing that
 * produces one is the method that checks.
 *
 * <p><strong>The reason is deliberately not carried in any structured form.</strong>
 * A caller that could ask "was it the signature or the timestamp" would eventually
 * answer the sender differently for each, and an endpoint that distinguishes "wrong
 * signature" from "stale timestamp" is an oracle for forging one. The message is for
 * the log; the response is the same either way.
 */
public class WebhookVerificationException extends RuntimeException {

    private final ProviderName provider;

    public WebhookVerificationException(ProviderName provider, String message) {
        super(message);
        this.provider = provider;
    }

    public WebhookVerificationException(ProviderName provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    /** Which adapter refused it. */
    public ProviderName provider() {
        return provider;
    }
}
