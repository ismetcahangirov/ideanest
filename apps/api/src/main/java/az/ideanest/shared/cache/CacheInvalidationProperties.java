package az.ideanest.shared.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the web client's invalidation endpoint is, and what proves a caller is us — #127.
 *
 * <h2>Off unless it is configured, and silent about it once</h2>
 *
 * <p>{@link #isConfigured()} is false when either the endpoint or the secret is missing, and a
 * service in that state does not invalidate anything. That is the correct default rather than a
 * degraded one: every cached read on the far side already carries a sixty-second window, so a
 * deployment with no endpoint configured behaves exactly as the platform did before this
 * existed. It says so once at start-up and then stops mentioning it, because a line per event
 * about a feature nobody enabled is a log nobody reads.
 *
 * <h2>The timeouts are short because of where this runs</h2>
 *
 * <p>The listener is called on the outbox relay's thread, which Spring shares with every other
 * scheduled job in the service. A call that hangs delays the reservation sweep and the
 * anonymiser behind it. {@link CacheInvalidator} answers that by never doing the call on that
 * thread at all, and these bound it anyway — the second lock on the same door, for the day
 * somebody calls the invalidator from somewhere else.
 *
 * @param endpoint the full URL of the web client's endpoint, e.g.
 *     {@code https://ideanest.az/api/cache/revalidate}. Absent disables invalidation
 * @param secret the shared secret sent as a bearer token. Absent disables invalidation
 * @param connectTimeout how long to wait for the connection
 * @param readTimeout how long to wait for the response
 * @param queueCapacity how many batches may be waiting to be sent before new ones are dropped.
 *     Dropping is the right failure: a cache hint that arrives late is worth less than a
 *     service that stalls trying to deliver it, and the far side's window is what makes a lost
 *     hint survivable
 */
@ConfigurationProperties(prefix = "ideanest.cache.invalidation")
public record CacheInvalidationProperties(
        String endpoint, String secret, Duration connectTimeout, Duration readTimeout, Integer queueCapacity) {

    public CacheInvalidationProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        queueCapacity = queueCapacity == null || queueCapacity < 1 ? 256 : queueCapacity;
    }

    /** Whether this deployment has both halves of what a call needs. */
    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank() && secret != null && !secret.isBlank();
    }
}
