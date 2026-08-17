package az.ideanest.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The correlation identifier, asserted on lines that were really emitted.
 *
 * <p>The appender is captured rather than the MDC inspected on its own: a value
 * in the MDC that no appender writes is not observability, and the failure this
 * guards against — a filter that sets the identifier after the line is logged,
 * or clears it before — is invisible to a test that only reads the MDC.
 */
class CorrelationFilterTests {

    private static final String VALID_INBOUND = "0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f";

    private final CorrelationFilter filter = new CorrelationFilter();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("az.ideanest.shared.observability.CorrelationFilterTests");
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseTheAppender() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("a line emitted during a request carries a correlation identifier")
    void everyLineCarriesACorrelationIdentifier() throws Exception {
        MockHttpServletResponse response = filterAndLog(new MockHttpServletRequest("GET", "/v1/pledges"));

        Map<String, String> mdc = emitted().getMDCPropertyMap();
        assertThat(mdc.get(Correlation.REQUEST_ID)).isNotBlank();
        assertThat(mdc.get(Correlation.TRACE_ID)).matches("[0-9a-f]{32}");
        assertThat(mdc.get(Correlation.SPAN_ID)).hasSize(16);
        assertThat(response.getHeader(Correlation.REQUEST_ID_HEADER)).isEqualTo(mdc.get(Correlation.REQUEST_ID));
    }

    @Test
    @DisplayName("a well-formed inbound X-Request-Id is honoured and echoed")
    void honoursAWellFormedInboundIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/pledges");
        request.addHeader(Correlation.REQUEST_ID_HEADER, VALID_INBOUND);

        MockHttpServletResponse response = filterAndLog(request);

        assertThat(emitted().getMDCPropertyMap()).containsEntry(Correlation.REQUEST_ID, VALID_INBOUND);
        assertThat(response.getHeader(Correlation.REQUEST_ID_HEADER)).isEqualTo(VALID_INBOUND);
    }

    @Test
    @DisplayName("a malformed inbound X-Request-Id is replaced, never echoed and never logged")
    void refusesAMalformedInboundIdentifier() throws Exception {
        String attack = "abc\r\nWARN somebody signed in as admin";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/pledges");
        request.addHeader(Correlation.REQUEST_ID_HEADER, attack);

        MockHttpServletResponse response = filterAndLog(request);

        String issued = emitted().getMDCPropertyMap().get(Correlation.REQUEST_ID);
        assertThat(issued).isNotBlank().doesNotContain("\n", "\r", "admin");
        assertThat(response.getHeader(Correlation.REQUEST_ID_HEADER)).isEqualTo(issued).doesNotContain("admin");
    }

    @Test
    @DisplayName("an over-long inbound X-Request-Id is replaced")
    void refusesAnUnboundedInboundIdentifier() throws Exception {
        String tooLong = "a".repeat(4096);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/pledges");
        request.addHeader(Correlation.REQUEST_ID_HEADER, tooLong);

        filterAndLog(request);

        assertThat(emitted().getMDCPropertyMap().get(Correlation.REQUEST_ID))
                .isNotEqualTo(tooLong)
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("a valid traceparent continues the caller's trace")
    void continuesTheCallersTrace() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/pledges");
        request.addHeader(Correlation.TRACEPARENT_HEADER, "00-" + traceId + "-00f067aa0ba902b7-01");

        filterAndLog(request);

        Map<String, String> mdc = emitted().getMDCPropertyMap();
        assertThat(mdc).containsEntry(Correlation.TRACE_ID, traceId);
        // A new span in the caller's trace, not the caller's span reused.
        assertThat(mdc.get(Correlation.SPAN_ID)).hasSize(16).isNotEqualTo("00f067aa0ba902b7");
    }

    @Test
    @DisplayName("a malformed traceparent is replaced rather than trusted")
    void refusesAMalformedTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/pledges");
        request.addHeader(Correlation.TRACEPARENT_HEADER, "99-not-a-trace-parent-at-all");

        filterAndLog(request);

        assertThat(emitted().getMDCPropertyMap().get(Correlation.TRACE_ID))
                .hasSize(32)
                .matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("the MDC is empty once the request is over")
    void clearsTheMdcAfterTheRequest() throws Exception {
        filterAndLog(new MockHttpServletRequest("GET", "/v1/pledges"));

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("the MDC is empty even when the request fails")
    void clearsTheMdcWhenTheChainThrows() {
        FilterChain exploding = (request, response) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/v1/pledges"), new MockHttpServletResponse(), exploding))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    /** Runs the filter over a chain that logs one line from inside the request. */
    private MockHttpServletResponse filterAndLog(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, loggingChain());
        return response;
    }

    private FilterChain loggingChain() {
        return (request, response) -> logger.info("handling the request");
    }

    private ILoggingEvent emitted() {
        assertThat(appender.list)
                .withFailMessage("Nothing was logged, so nothing below proves anything")
                .isNotEmpty();
        return appender.list.get(0);
    }
}
