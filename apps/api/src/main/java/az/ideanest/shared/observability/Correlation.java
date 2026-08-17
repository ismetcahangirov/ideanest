package az.ideanest.shared.observability;

import az.ideanest.shared.Identifiers;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The identifiers §18.1 puts on every line, and the rules for accepting one from
 * outside.
 *
 * <p>The MDC key names are the ones the specification names, so that a query
 * written against the documentation works against the logs.
 *
 * <p><strong>An inbound identifier is input, not information.</strong> A client
 * sends {@code X-Request-Id} so that its own logs and ours can be joined, which
 * is worth honouring — but the value ends up in every line of the request, so
 * echoing it unchecked would let anybody with an HTTP client write whatever they
 * liked into the log stream. A newline forges a log entry; a megabyte forges an
 * outage. Anything outside {@link #ACCEPTABLE_IDENTIFIER} is therefore dropped
 * and replaced by one of ours rather than sanitised into something that no longer
 * joins to anything.
 */
public final class Correlation {

    /** §18.1's five fields. */
    public static final String REQUEST_ID = "requestId";

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String USER_ID = "userId";
    public static final String PROJECT_ID = "projectId";

    /** What a client sends, and what comes back on the response. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** W3C Trace Context. Lower case, per the specification. */
    public static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * Long enough for a UUID with its hyphens, and for the identifiers other
     * platforms mint. Short enough that the header cannot become the payload.
     */
    private static final Pattern ACCEPTABLE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * {@code version-traceid-spanid-flags}. Version {@code ff} is invalid by the
     * specification, an all-zero trace or span identifier likewise. Trailing
     * fields from a future version are tolerated: this reads the two values it
     * needs and ignores the rest, which is what the specification asks of a
     * participant that does not understand a newer version.
     */
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^(?!ff)[0-9a-f]{2}-(?!0{32})([0-9a-f]{32})-(?!0{16})([0-9a-f]{16})-[0-9a-f]{2}(?:-.*)?$");

    /** A header longer than this is not parsed at all. */
    private static final int LONGEST_ACCEPTED_HEADER = 256;

    private Correlation() {
    }

    /** The candidate if it is one this service is willing to write to a log, otherwise null. */
    public static String acceptableIdentifier(String candidate) {
        if (candidate == null || candidate.length() > LONGEST_ACCEPTED_HEADER) {
            return null;
        }
        return ACCEPTABLE_IDENTIFIER.matcher(candidate).matches() ? candidate : null;
    }

    /** The caller's trace, if the header really is one, otherwise null. */
    public static String traceIdFrom(String traceparent) {
        if (traceparent == null || traceparent.length() > LONGEST_ACCEPTED_HEADER) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * A new request identifier: UUID v7, like every other identifier in the
     * service, so that a log line sorts the way the rows it describes do.
     */
    public static String newRequestId() {
        return Identifiers.newIdentifier().toString();
    }

    /** Thirty-two lower-case hex characters, as W3C Trace Context requires. */
    public static String newTraceId() {
        return Identifiers.newIdentifier().toString().replace("-", "");
    }

    /** Sixteen lower-case hex characters. Never the all-zero span. */
    public static String newSpanId() {
        UUID source = Identifiers.newIdentifier();
        long bits = source.getLeastSignificantBits();
        return String.format("%016x", bits == 0 ? 1 : bits);
    }

    /**
     * Puts a fresh set into the MDC, overwriting whatever was there.
     *
     * <p>For work that did not arrive in a request — a scheduled sweep, say.
     * There is no caller to inherit from, and §18.1 wants the line correlated
     * anyway: without this, the one place a job's lines could be gathered from is
     * the timestamp.
     */
    public static void mintIntoMdc() {
        MDC.put(REQUEST_ID, newRequestId());
        MDC.put(TRACE_ID, newTraceId());
        MDC.put(SPAN_ID, newSpanId());
    }
}
