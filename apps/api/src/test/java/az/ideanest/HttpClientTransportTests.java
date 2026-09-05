package az.ideanest;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * The outbound HTTP transport, asserted rather than assumed — issue #396.
 *
 * <h2>Why this is a test and not a line in a document</h2>
 *
 * <p>Spring chooses a {@link ClientHttpRequestFactory} by looking for one on the classpath
 * unless it is told which to use, and it prefers Apache HttpClient to the JDK's client. That
 * makes the transport of every outbound call this service places — the Central Bank rates,
 * Expo push, the cache invalidator — a property of the dependency graph rather than a
 * decision anybody made. Taking the AWS SDK bom to 2.54 demonstrated it: {@code awssdk:s3}
 * began depending on {@code apache5-client}, Apache HttpClient 5 appeared on the classpath,
 * and every {@code RestTemplate} in the process quietly changed connection pool, timeouts
 * and retry behaviour. Nothing failed to compile and no test named the change.
 *
 * <p>{@code application.yml} pins {@code spring.http.clients.imperative.factory} to answer it.
 * This is the assertion that the pin still holds, so the next dependency that puts a client on
 * the classpath fails here, in one named test, rather than in production.
 *
 * <h2>And the harness, for the same reason</h2>
 *
 * <p>{@link TestRestTemplate} is built from the same auto-configuration, so it took the same
 * transport — and Apache HttpClient retries automatically and honours {@code Retry-After}. A
 * test that provokes a 429 to assert on it therefore did not receive the refusal; it slept for
 * the whole rate-limit window and the Backend job hit CI's twenty-minute ceiling.
 * {@code RegistrationApiTests.repeatedAttemptsOnOneAddressAreRateLimited} is that test. A
 * client asserting on a refusal must not retry it, and that is checked here.
 */
class HttpClientTransportTests extends AbstractIntegrationTest {

    @Autowired
    private ClientHttpRequestFactory requestFactory;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("the application places outbound calls over the JDK client")
    void applicationTransportIsTheJdkClient() {
        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("the test harness uses the same client, so a 429 is returned and not retried")
    void harnessTransportIsTheJdkClient() {
        assertThat(rest.getRestTemplate().getRequestFactory())
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }
}
