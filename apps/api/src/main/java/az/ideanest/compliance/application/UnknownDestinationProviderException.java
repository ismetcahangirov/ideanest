package az.ideanest.compliance.application;

/**
 * A payout destination was filed against a provider §9.3 has never heard of — #432.
 *
 * <p>{@code UnknownProviderException}'s job one module along, and it is a separate type
 * because that one is {@code payment.domain} and this module may not name it. What crosses is
 * the question — {@code PaymentProviders.canonicalNameOf} — and the refusal is expressed in
 * the vocabulary of the caller that was refused.
 *
 * <p>A 400 rather than a 500. The value came from a request body, a different value would be
 * accepted, and the alternative is letting V72's CHECK constraint refuse it three layers down
 * as a database error nobody can read.
 */
public class UnknownDestinationProviderException extends RuntimeException {

    private final transient String provider;

    public UnknownDestinationProviderException(String provider) {
        super("No payment provider is called '" + provider + "'");
        this.provider = provider;
    }

    /** What was sent. Echoed to the client, which is what makes a typo visible as a typo. */
    public String provider() {
        return provider;
    }
}
