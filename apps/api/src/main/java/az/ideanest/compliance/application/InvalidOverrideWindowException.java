package az.ideanest.compliance.application;

import java.time.Duration;
import java.time.Instant;

/**
 * The window asked for is not one that may be granted — #436.
 *
 * <p>One exception for both ends, because both are the same mistake made on the same field
 * and the operator's correction is the same: pick another date. Two codes in front of one
 * input would be two error messages the screen has to choose between, for a distinction the
 * person filling it in does not have.
 *
 * <p>The ceiling is carried on the exception rather than looked up by the handler, so that
 * the refusal says what would have been accepted. An error that says "no" without saying
 * "up to ninety days" is one the operator answers by guessing.
 */
public class InvalidOverrideWindowException extends RuntimeException {

    private final transient Instant expiresAt;
    private final transient Instant now;
    private final transient Duration longest;

    public InvalidOverrideWindowException(Instant expiresAt, Instant now, Duration longest) {
        super("An override must expire after now and within %s (asked for %s at %s)."
                .formatted(longest, expiresAt, now));
        this.expiresAt = expiresAt;
        this.now = now;
        this.longest = longest;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant now() {
        return now;
    }

    public Duration longest() {
        return longest;
    }
}
