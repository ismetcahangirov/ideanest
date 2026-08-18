package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The rate-limit headers on a real response, written by the real filter chain.
 *
 * <p>{@code RateLimitReportingTests} asserts the numbers; this asserts that they
 * survive the journey. Two things could take them away and neither is visible from
 * a unit test: a response the handler had already committed, and the exception
 * handler replacing the response rather than adding to it. The refused path is the
 * one that matters — a 429 is the response a client most needs the headers on, and
 * it is the response written by the advice rather than by the endpoint.
 */
class RateLimitHeadersApiTests extends AbstractIntegrationTest {

    /** Its own addresses, because the per-email registration limit is real in this profile. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("a served search says what is left of the allowance")
    void aServedSearchReportsItsAllowance() {
        ResponseEntity<String> first = rest.getForEntity("/v1/search?q=nothing-matches-this", String.class);
        ResponseEntity<String> second = rest.getForEntity("/v1/search?q=nothing-matches-that", String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(headerAsInt(first, "X-RateLimit-Limit")).isPositive();
        assertThat(headerAsInt(first, "X-RateLimit-Reset")).isPositive();

        int remaining = headerAsInt(second, "X-RateLimit-Remaining");

        // One request, one unit. A count that did not move would be a header
        // that looks right and tells a client nothing.
        assertThat(remaining).isEqualTo(headerAsInt(first, "X-RateLimit-Remaining") - 1);
        assertThat(second.getHeaders().getFirst("RateLimit"))
                .isEqualTo("\"default\";r=%d;t=%d".formatted(remaining, headerAsInt(second, "X-RateLimit-Reset")));
    }

    @Test
    @DisplayName("a refusal carries the headers as well as the problem")
    void aRefusalReportsTheSpentAllowance() {
        String email = "rate-limit-headers" + SEQUENCE.incrementAndGet() + "@example.com";

        ResponseEntity<String> refused = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            refused = rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email, "password", "a-long-enough-password", "name", "İsmət Cahangirov"),
                    String.class);
        }

        assertThat(refused).isNotNull();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(refused.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        // Registration spends two budgets, one per address and one per email.
        // The tighter is the one the client is about to be refused on, and this
        // profile leaves the per-address one effectively off, so it is the email
        // budget that has to be reported.
        assertThat(headerAsInt(refused, "X-RateLimit-Limit")).isEqualTo(3);
        assertThat(refused.getBody()).contains("https://ideanest.az/problems/rate-limited");
    }

    private static int headerAsInt(ResponseEntity<String> response, String name) {
        String value = response.getHeaders().getFirst(name);
        assertThat(value).as(name).isNotNull();
        return Integer.parseInt(value);
    }
}
