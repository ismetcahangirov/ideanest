package az.ideanest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The health endpoint is what the load balancer and the orchestrator read to
 * decide whether this instance receives traffic. It is part of the contract with
 * the platform, so it is tested like one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("health reports UP without leaking component detail")
    void healthIsUpAndOpaque() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        // Anonymous callers learn that the service is up and nothing else. A
        // component breakdown names our dependencies — database, cache, mail
        // relay — to anyone who asks.
        assertThat(response.getBody()).doesNotContain("components").doesNotContain("details");
    }

    @Test
    @DisplayName("liveness and readiness are separately addressable")
    void probesAreExposedIndependently() {
        // Distinct probes matter under a rolling deploy: an instance that is
        // alive but not ready should be taken out of rotation, not restarted.
        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("no endpoint beyond health is exposed")
    void otherEndpointsStayClosed() {
        // env and configprops print configuration, which includes secrets.
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/actuator/beans", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
