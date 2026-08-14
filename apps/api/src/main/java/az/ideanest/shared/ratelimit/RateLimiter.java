package az.ideanest.shared.ratelimit;

import java.time.Duration;

/**
 * A counter with a window, used to make guessing expensive.
 *
 * <p>The interface exists so that the in-memory implementation can be replaced
 * by a Redis one without touching a caller. That replacement is not optional
 * once there is more than one instance: see {@link InMemoryRateLimiter}.
 */
public interface RateLimiter {

    /**
     * Records an attempt against {@code key} and says whether it is allowed.
     *
     * <p>Counts the attempt either way. A limiter that stops counting once the
     * limit is reached lets a client that keeps hammering slide back under the
     * limit as the window moves, which is the opposite of what it is for.
     */
    RateLimitDecision recordAttempt(String key, int limit, Duration window);

    /** The outcome, and how long to wait if it was refused. */
    record RateLimitDecision(boolean allowed, Duration retryAfter) {

        public static RateLimitDecision allow() {
            return new RateLimitDecision(true, Duration.ZERO);
        }

        public static RateLimitDecision refuse(Duration retryAfter) {
            return new RateLimitDecision(false, retryAfter);
        }
    }
}
