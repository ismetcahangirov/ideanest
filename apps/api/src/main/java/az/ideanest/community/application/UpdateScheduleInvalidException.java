package az.ideanest.community.application;

import java.time.Instant;

/**
 * A {@code publishAt} the platform will not accept.
 *
 * <p>Carries the moment that was asked for and the earliest one that would have been
 * allowed, because the three refusals below are all "not then, but from here onwards"
 * and a client that is told the boundary can move the picker to it rather than making
 * the creator guess.
 *
 * @param requested what the client sent
 * @param earliestAllowed the boundary it fell on the wrong side of, or null when the
 *     refusal is that the moment is too far away rather than too soon
 */
public class UpdateScheduleInvalidException extends RuntimeException {

    private final transient Instant requested;

    private final transient Instant earliestAllowed;

    public UpdateScheduleInvalidException(String message, Instant requested, Instant earliestAllowed) {
        super(message);
        this.requested = requested;
        this.earliestAllowed = earliestAllowed;
    }

    public Instant requested() {
        return requested;
    }

    public Instant earliestAllowed() {
        return earliestAllowed;
    }
}
