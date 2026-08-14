package az.ideanest.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock, as a bean.
 *
 * <p>Calling {@code Instant.now()} inside a service makes every rule that
 * depends on time — token expiry, session lifetime, the collection window at a
 * campaign close — testable only by sleeping. An injected clock makes those
 * tests instant and deterministic, and it costs one parameter.
 *
 * <p>UTC everywhere. A server whose zone changes under it, or two servers in
 * different zones, produce timestamps that cannot be compared, and every one of
 * those comparisons here decides whether a credential is still valid.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
