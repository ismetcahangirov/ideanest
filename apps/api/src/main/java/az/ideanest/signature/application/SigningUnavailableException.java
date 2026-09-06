package az.ideanest.signature.application;

/**
 * Nothing can be signed here — issue #429.
 *
 * <p>Thrown when no signature provider is configured, which is the platform's state until
 * #423's personal-data row is answered: {@code SignatureProviders} refuses to point at
 * production SİMA before then and logs that nothing will be signed.
 *
 * <p>Its own exception rather than {@code SignatureProviderUnavailableException}, which means
 * something different and worse — a provider that is configured and cannot be reached. That one
 * is an incident. This one is a configuration the platform is deliberately in, and a creator
 * meeting it should be told that signing is not available yet rather than that something broke.
 */
public class SigningUnavailableException extends RuntimeException {

    public SigningUnavailableException() {
        super("No signature provider is configured; nothing can be signed.");
    }
}
