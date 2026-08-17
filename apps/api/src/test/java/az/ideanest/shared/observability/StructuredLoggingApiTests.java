package az.ideanest.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * The filter as the running application registers it.
 *
 * <p>The unit tests call the filter directly, which proves that it works and not
 * that anything installed it. This one goes over the wire: an identifier a client
 * sent comes back, and every line logged while handling the request carries it.
 *
 * <p>The captured logger is the dispatcher servlet's, deliberately. It runs on the
 * container thread inside the filter chain, so a line it emits is a line from the
 * request — unlike a client-side logger, which would be on the test's own thread
 * and would pass this test for the wrong reason.
 */
class StructuredLoggingApiTests extends AbstractIntegrationTest {

    private static final String SERVER_SIDE_LOGGER = "org.springframework.web.servlet.DispatcherServlet";

    private static final String INBOUND = "0192f0c1-8f3a-7c2b-9d4e-0badc0ffee00";

    @Autowired
    private TestRestTemplate rest;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(SERVER_SIDE_LOGGER);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        // The suite runs at INFO, and the dispatcher has nothing to say at that
        // level. Without this there would be no emitted line to assert on.
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseTheAppender() {
        logger.setLevel(null);
        logger.detachAppender(appender);
        appender.stop();
    }

    private ResponseEntity<String> health(HttpHeaders headers) {
        return rest.exchange("/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders requestId(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(Correlation.REQUEST_ID_HEADER, value);
        return headers;
    }

    @Test
    @DisplayName("the correlation identifier a client sent comes back on the response")
    void echoesTheInboundCorrelationIdentifier() {
        ResponseEntity<String> response = health(requestId(INBOUND));

        assertThat(response.getHeaders().getFirst(Correlation.REQUEST_ID_HEADER)).isEqualTo(INBOUND);
        assertThat(response.getHeaders().getFirst(Correlation.TRACE_ID_HEADER)).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("a request that sent nothing still gets an identifier")
    void mintsAnIdentifierWhenTheClientSentNone() {
        ResponseEntity<String> response = health(new HttpHeaders());

        assertThat(response.getHeaders().getFirst(Correlation.REQUEST_ID_HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("a malformed identifier is not echoed back")
    void refusesAMalformedInboundIdentifier() {
        ResponseEntity<String> response = health(requestId("not a request id at all"));

        assertThat(response.getHeaders().getFirst(Correlation.REQUEST_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo("not a request id at all");
    }

    @Test
    @DisplayName("every line logged while handling a request carries the identifier")
    void everyLineDuringARequestIsCorrelated() {
        health(requestId(INBOUND));

        assertThat(appender.list)
                .withFailMessage("Nothing was logged while handling the request, so nothing below proves anything")
                .isNotEmpty();
        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry(Correlation.REQUEST_ID, INBOUND)
                        .containsKey(Correlation.TRACE_ID)
                        .containsKey(Correlation.SPAN_ID));
    }
}
