package az.ideanest.shared.ratelimit;

import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Acts on a decision: refuses when it says so, and tells the caller where it stands
 * either way.
 *
 * <p>Every endpoint used to do the first half and none did the second, so the first
 * a client heard of a limit was a 429. §10.3 lists {@code X-RateLimit-*} among this
 * API's conventions precisely so that it does not have to be: a client that can see
 * its allowance shrinking can slow down, and one that cannot only finds out by being
 * refused — and then, having no idea how close it was, usually retries straight into
 * the same wall.
 *
 * <h2>Which fields, and why both</h2>
 *
 * <p>{@code X-RateLimit-Limit}, {@code -Remaining} and {@code -Reset} are what §10.3
 * names and what every client library on the platform already understands. They are
 * also not a standard: the IETF's own draft observes that implementations disagree on
 * what the numbers mean, and the disagreement that matters here is
 * {@code X-RateLimit-Reset} — some services send a Unix timestamp, others the seconds
 * until it. <strong>Seconds, here</strong>, because {@code Retry-After} on the refusal
 * is seconds and one API answering the same question in two units is a bug waiting
 * for a client to guess wrong.
 *
 * <p><strong>{@code RateLimit} and {@code RateLimit-Policy} are sent as well</strong>
 * — draft-ietf-httpapi-ratelimit-headers, standards track, at draft 11 in May 2026.
 * Sending only those would be correct and useless: the draft is not an RFC, and no
 * client we have parses them. Sending only the legacy trio would mean revisiting
 * every client the day the draft lands. Both costs about eighty bytes on a response
 * that is already carrying a page of campaign cards, and it lets a client move to the
 * standard fields whenever it likes rather than when we do.
 *
 * <p><strong>The policy is unnamed on purpose.</strong> The draft lets a policy carry
 * a name, and the obvious names here would be the buckets themselves — the address
 * one, the email one. Registration spends both, and reporting which of the two ran
 * out would tell whoever asked that somebody else has been trying that email address.
 * A caller learns its own remaining allowance and not which budget produced it.
 *
 * <h2>Where the numbers are written</h2>
 *
 * <p>Onto the response as the decision is made, rather than in a filter or an
 * interceptor afterwards. By the time either of those runs, a handler returning a
 * body has already had it written by a message converter, and a header set on a
 * committed response is a header nobody receives. It also means the refusal below
 * carries the same fields: they are on the response before the exception is thrown,
 * and {@code ApiExceptionHandler} adds to that response rather than replacing it.
 *
 * <p><strong>When several budgets apply to one request, the tightest is reported.</strong>
 * Registration counts per address and per email; a client told about whichever
 * happened to be checked last would be told it had ninety-nine attempts left on the
 * request that is about to be refused.
 *
 * <p>A public read is {@code Cache-Control: public}, so a shared cache will store
 * these fields along with the body and hand another client a count that was never
 * about it. That is the draft's own case: fields on a response served from cache are
 * to be ignored. It costs nothing here because a cache hit never reaches the limiter
 * — the request it would have counted did not arrive.
 */
public final class RateLimits {

    /** §10.3's convention. See the class comment for what {@code Reset} counts in. */
    private static final String LIMIT_HEADER = "X-RateLimit-Limit";

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final String RESET_HEADER = "X-RateLimit-Reset";

    /** draft-ietf-httpapi-ratelimit-headers §3: the service limit, and the quota it belongs to. */
    private static final String RATE_LIMIT_HEADER = "RateLimit";

    private static final String RATE_LIMIT_POLICY_HEADER = "RateLimit-Policy";

    /** A structured-field string, quoted as the draft requires. See the class comment for why it says nothing. */
    private static final String POLICY_NAME = "\"default\"";

    /** The tightest decision reported on this request so far. */
    private static final String REPORTED = RateLimits.class.getName() + ".reported";

    private RateLimits() {
    }

    /**
     * Reports the decision to the caller, and refuses the request if it was refused.
     *
     * <p>Reporting happens either way, which is the whole point: a limit nobody is
     * told about until it is hit is a limit that produces a retry storm rather than a
     * client backing off.
     */
    public static void enforce(RateLimitDecision decision) {
        report(decision);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }

    /**
     * Whole seconds, rounded up.
     *
     * <p>Rounding down is the interesting direction: a client told to wait zero
     * seconds when four hundred milliseconds of the window remain retries immediately
     * and is refused again, which is the loop these headers exist to prevent. It also
     * keeps {@code Retry-After} from pointing inside the window {@code RateLimit}
     * reports, which the draft asks for explicitly.
     */
    public static long secondsRoundedUp(Duration duration) {
        if (duration.isNegative()) {
            return 0;
        }
        long seconds = duration.toSeconds();
        return duration.getNano() > 0 ? seconds + 1 : seconds;
    }

    private static void report(RateLimitDecision decision) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            // Nothing to write on. A limiter reached from outside a request —
            // a scheduled job, a test of the counter itself — still counts, and
            // must not fail for want of a response.
            return;
        }
        HttpServletResponse response = attributes.getResponse();
        if (response == null || response.isCommitted() || !claimsTheHeaders(decision, attributes)) {
            return;
        }

        long remaining = decision.remaining();
        long reset = secondsRoundedUp(decision.resetAfter());
        long window = secondsRoundedUp(decision.window());

        response.setHeader(LIMIT_HEADER, Integer.toString(decision.limit()));
        response.setHeader(REMAINING_HEADER, Long.toString(remaining));
        response.setHeader(RESET_HEADER, Long.toString(reset));
        response.setHeader(RATE_LIMIT_HEADER, "%s;r=%d;t=%d".formatted(POLICY_NAME, remaining, reset));
        response.setHeader(
                RATE_LIMIT_POLICY_HEADER, "%s;q=%d;w=%d".formatted(POLICY_NAME, decision.limit(), window));
    }

    /**
     * Whether this decision is the one the caller should hear about, and records it
     * as such if it is.
     *
     * <p>A refusal always wins: it is the decision the client has to act on. Between
     * two allowed decisions the one with less left wins, and a tie keeps the first —
     * so the report is stable when a request spends two budgets of the same size.
     */
    private static boolean claimsTheHeaders(RateLimitDecision decision, ServletRequestAttributes attributes) {
        Object reported = attributes.getAttribute(REPORTED, RequestAttributes.SCOPE_REQUEST);
        if (decision.allowed()
                && reported instanceof RateLimitDecision earlier
                && earlier.remaining() <= decision.remaining()) {
            return false;
        }
        attributes.setAttribute(REPORTED, decision, RequestAttributes.SCOPE_REQUEST);
        return true;
    }
}
