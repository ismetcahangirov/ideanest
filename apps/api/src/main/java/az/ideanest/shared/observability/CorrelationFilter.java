package az.ideanest.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts §18.1's correlation identifiers on every line logged during a request.
 *
 * <p>First in the chain, before authentication. The lines that matter most —
 * §18.1's "authentication failures, and rate-limit rejections" — are emitted by
 * requests that never reach a handler, and a filter placed after the security
 * chain would miss all of them.
 *
 * <p>The identifiers are established once and kept on the request, so an async
 * dispatch continues the same request rather than starting a second one. The MDC
 * is cleared in a {@code finally}, including when the chain throws: a container
 * thread is reused, and a value left behind is the previous caller's identifier
 * written onto the next caller's lines — which is worse than no identifier,
 * because it looks correct.
 */
public class CorrelationFilter extends OncePerRequestFilter {

    private static final String ESTABLISHED = CorrelationFilter.class.getName() + ".established";

    /**
     * Run again on an async dispatch. The default is not to, which would leave
     * the identifiers off every line logged after the handler returned — on a
     * different thread, where the MDC of the request thread does not reach.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Correlated correlated = establish(request);
        MDC.put(Correlation.REQUEST_ID, correlated.requestId());
        MDC.put(Correlation.TRACE_ID, correlated.traceId());
        MDC.put(Correlation.SPAN_ID, correlated.spanId());
        try {
            // On the response as well as in the log, so that a user reporting a
            // problem can quote the identifier of the request that failed.
            if (!response.isCommitted()) {
                response.setHeader(Correlation.REQUEST_ID_HEADER, correlated.requestId());
                response.setHeader(Correlation.TRACE_ID_HEADER, correlated.traceId());
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.REQUEST_ID);
            MDC.remove(Correlation.TRACE_ID);
            MDC.remove(Correlation.SPAN_ID);
        }
    }

    private static Correlated establish(HttpServletRequest request) {
        if (request.getAttribute(ESTABLISHED) instanceof Correlated existing) {
            return existing;
        }
        String inboundRequestId = Correlation.acceptableIdentifier(request.getHeader(Correlation.REQUEST_ID_HEADER));
        String inboundTraceId = Correlation.traceIdFrom(request.getHeader(Correlation.TRACEPARENT_HEADER));
        Correlated correlated = new Correlated(
                inboundRequestId != null ? inboundRequestId : Correlation.newRequestId(),
                inboundTraceId != null ? inboundTraceId : Correlation.newTraceId(),
                // Ours either way. Continuing the caller's trace does not mean
                // adopting the caller's span: the work done here is a new span
                // whose parent is theirs.
                Correlation.newSpanId());
        request.setAttribute(ESTABLISHED, correlated);
        return correlated;
    }

    private record Correlated(String requestId, String traceId, String spanId) {
    }
}
