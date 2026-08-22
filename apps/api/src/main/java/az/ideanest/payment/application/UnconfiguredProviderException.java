package az.ideanest.payment.application;

import az.ideanest.payment.domain.ProviderName;

/**
 * A delivery arrived for a provider in §9.3's list that has no adapter on the classpath.
 *
 * <p>Distinct from {@code UnknownProviderException}, which is a path segment naming
 * nothing at all. This one names something real that the platform cannot verify,
 * because no adapter for it is deployed — which today is every provider, since #60 has
 * not chosen one.
 *
 * <p><strong>Answered as a 404 rather than a 503</strong>, and the difference is what
 * the sender does next. A 503 invites a provider to retry, and there is nothing to
 * retry into: a delivery for an unconfigured provider will still be unconfigured in an
 * hour, and the retries would run until the provider's own window expired. A 404 says
 * this endpoint does not exist for you, which is true.
 *
 * <p>It is also the answer a caller probing {@code /v1/webhooks/psp/{provider}} gets for
 * every provider today, which is deliberate: the endpoint publishes nothing about which
 * providers the platform is talking to.
 */
public class UnconfiguredProviderException extends RuntimeException {

    private final ProviderName provider;

    public UnconfiguredProviderException(ProviderName provider) {
        super("No adapter for " + provider + " is deployed; nothing can verify its deliveries");
        this.provider = provider;
    }

    public ProviderName provider() {
        return provider;
    }
}
