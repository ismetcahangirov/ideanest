package az.ideanest.shared.ratelimit;

import java.time.Duration;

/** Thrown when a caller has used its allowance for a window. */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        // No key in the message. The key contains an email address or an IP,
        // and this message reaches a log.
        super("Rate limit exceeded");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
