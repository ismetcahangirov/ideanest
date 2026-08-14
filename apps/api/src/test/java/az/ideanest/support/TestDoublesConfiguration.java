package az.ideanest.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The collaborators tests substitute.
 *
 * <p>Imported by {@link AbstractIntegrationTest} rather than per test class, so
 * that every integration test shares one context — and therefore one PostgreSQL
 * container. A test class that imported a different set would get a second
 * context and a second container for its trouble.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

    @Bean
    @Primary
    RecordingVerificationNotifier recordingVerificationNotifier() {
        return new RecordingVerificationNotifier();
    }
}
