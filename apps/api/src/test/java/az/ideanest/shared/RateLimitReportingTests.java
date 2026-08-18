package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.api.ApiExceptionHandler;
import az.ideanest.shared.ratelimit.InMemoryRateLimiter;
import az.ideanest.shared.ratelimit.RateLimitExceededException;
import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import az.ideanest.shared.ratelimit.RateLimits;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * What a caller is told about its allowance, on the request that was served and on
 * the one that was not.
 *
 * <p>§10.3 lists {@code X-RateLimit-*} among the API's conventions and nothing was
 * emitting them, so a client had no way to slow down before being refused — the
 * first news of a limit was a 429. These are the assertions that the numbers in
 * those headers are the limiter's actual numbers rather than a plausible-looking
 * constant, which is the failure mode a header like this has: it is believed.
 */
class RateLimitReportingTests {

    private static final Instant START = Instant.parse("2026-08-18T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final Clock FIXED = Clock.fixed(START, ZoneOffset.UTC);

    private MockHttpServletResponse response;

    /**
     * Binds a request and a response to this thread, which is what Spring MVC does
     * around every handler and what {@link RateLimits} reads to find the response it
     * is reporting on.
     */
    @BeforeEach
    void bindARequest() {
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest(), response));
    }

    @AfterEach
    void unbindTheRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("a served request is told what is left of its allowance")
    void reportsTheRemainingAllowance() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);

        RateLimits.enforce(limiter.recordAttempt("search:ip:1.2.3.4", 60, WINDOW));

        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("59");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("60");
    }

    @Test
    @DisplayName("the same facts are also given in the IETF draft's fields")
    void reportsTheStandardFieldsToo() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);

        RateLimits.enforce(limiter.recordAttempt("search:ip:1.2.3.4", 60, WINDOW));

        assertThat(response.getHeader("RateLimit")).isEqualTo("\"default\";r=59;t=60");
        assertThat(response.getHeader("RateLimit-Policy")).isEqualTo("\"default\";q=60;w=60");
    }

    @Test
    @DisplayName("the remaining count falls by one per attempt and reaches zero at the limit")
    void countsDownToZero() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);

        for (int attempt = 1; attempt <= 3; attempt++) {
            MockHttpServletResponse each = rebind();
            RateLimits.enforce(limiter.recordAttempt("search:ip:1.2.3.4", 3, WINDOW));
            assertThat(each.getHeader("X-RateLimit-Remaining")).isEqualTo(String.valueOf(3 - attempt));
        }
    }

    @Test
    @DisplayName("a refused request is told the allowance is spent, and when it is not")
    void reportsOnTheRefusedPath() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);
        for (int attempt = 0; attempt < 3; attempt++) {
            RateLimits.enforce(limiter.recordAttempt("search:ip:1.2.3.4", 3, WINDOW));
        }
        MockHttpServletResponse refused = rebind();

        assertThatThrownBy(() -> RateLimits.enforce(limiter.recordAttempt("search:ip:1.2.3.4", 3, WINDOW)))
                .isInstanceOf(RateLimitExceededException.class);

        // The headers are on the response before the exception leaves, so the
        // refusal carries them as well as the 200s that led to it.
        assertThat(refused.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(refused.getHeader("X-RateLimit-Reset")).isEqualTo("60");
        assertThat(refused.getHeader("RateLimit")).isEqualTo("\"default\";r=0;t=60");
    }

    @Test
    @DisplayName("when two limits apply to one request, the tighter one is reported")
    void reportsTheTightestPolicy() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);

        // Registration is the shape this exists for: an address budget and an
        // email budget, both spent by one request. Reporting the second would
        // tell a client it had 99 attempts left on the one it is about to be
        // refused on.
        RateLimits.enforce(limiter.recordAttempt("register:email:a@example.com", 3, WINDOW));
        RateLimits.enforce(limiter.recordAttempt("register:ip:1.2.3.4", 100, WINDOW));

        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    @DisplayName("a limiter used outside a request reports nothing rather than failing")
    void survivesWithoutARequest() {
        RequestContextHolder.resetRequestAttributes();
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);

        // A scheduled job that ever came to use a limiter has no response to
        // write on, and it must not be a NullPointerException in the middle of
        // whatever it was doing.
        RateLimits.enforce(limiter.recordAttempt("job:sweep", 3, WINDOW));
    }

    @Test
    @DisplayName("a refusal is an RFC 9457 problem with a Retry-After")
    void refusalIsAProblemDetail() {
        ResponseEntity<ProblemDetail> refusal =
                new ApiExceptionHandler().handleRateLimit(new RateLimitExceededException(Duration.ofSeconds(42)));

        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refusal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");

        ProblemDetail problem = refusal.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType()).hasToString("https://ideanest.az/problems/rate-limited");
        assertThat(problem.getTitle()).isEqualTo("Too many requests");
        assertThat(problem.getProperties()).containsEntry("retryAfterSeconds", 42L);
    }

    @Test
    @DisplayName("a wait of part of a second is rounded up, never down to nothing")
    void roundsThePauseUp() {
        // Rounding down would tell a client to wait zero seconds and be refused
        // again on the retry, which is the loop Retry-After exists to prevent.
        // The IETF draft asks for the same relation: Retry-After may not fall
        // inside the window the RateLimit field reports.
        ResponseEntity<ProblemDetail> refusal =
                new ApiExceptionHandler().handleRateLimit(new RateLimitExceededException(Duration.ofMillis(1500)));

        assertThat(refusal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    @DisplayName("a decision made against a spent window still reports a whole second")
    void reportsAResetOfAtLeastZero() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(FIXED);
        RateLimitDecision decision = limiter.recordAttempt("search:ip:1.2.3.4", 1, Duration.ofMillis(1500));

        RateLimits.enforce(decision);

        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("2");
    }

    /** A fresh response, bound like the one the previous request finished on. */
    private MockHttpServletResponse rebind() {
        MockHttpServletResponse next = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), next));
        response = next;
        return next;
    }
}
