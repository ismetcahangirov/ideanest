package az.ideanest.payment.domain;

/**
 * The provider could not be asked, or answered something nobody can read.
 *
 * <p><strong>Distinct from a decline, and the distinction is the point.</strong> A
 * {@link ChargeResult} with {@link ProviderOutcome#DECLINED} means the platform knows
 * nothing moved. This means the platform does not know: the request may have reached
 * the provider, the card may have been charged, and the answer may have been lost on
 * the way back. §9.6's schedule must not count that as one of a backer's four
 * attempts, and the collection run must not report the campaign short over it.
 *
 * <p>It is also what {@code ProviderCircuitBreaker} counts. A decline is somebody's
 * card; a run of these is the provider being down on the day a large campaign closes,
 * which §9.3 names as the risk that stops the entire business — so the breaker opens
 * on these and never on declines.
 *
 * @see ChargeResult for why an ordinary refusal is a value rather than a throw
 */
public class ProviderUnavailableException extends RuntimeException {

    private final ProviderName provider;

    public ProviderUnavailableException(ProviderName provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public ProviderUnavailableException(ProviderName provider, String message) {
        this(provider, message, null);
    }

    /** Which provider was unreachable. On the exception because a fleet has two. */
    public ProviderName provider() {
        return provider;
    }
}
