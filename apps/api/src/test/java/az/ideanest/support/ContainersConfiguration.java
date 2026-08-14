package az.ideanest.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The containers integration tests run against.
 *
 * <p>Declared as beans rather than started by hand so that Spring owns the
 * lifecycle and {@code @ServiceConnection} supplies the connection details. The
 * practical effect is that every test class sharing this configuration also
 * shares one context and therefore one container: PostgreSQL starts once for
 * the whole test run, not once per class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfiguration {

    /**
     * Pinned to the same major version as the deployed database. A test suite
     * that passes on a different major version than production runs is a test
     * suite that has not tested production.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
