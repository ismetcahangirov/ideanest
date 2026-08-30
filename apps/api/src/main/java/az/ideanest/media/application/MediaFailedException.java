package az.ideanest.media.application;

import az.ideanest.media.domain.MediaFailureReason;
import java.util.Objects;

/**
 * The upload will not become an image, and the creator can do something about it — the
 * media pipeline design of 2026-08-30.
 *
 * <p>Carries a {@link MediaFailureReason} rather than a message, for the reason that enum
 * gives: the words a creator reads live in the message catalogue with every other string
 * they read, and the database holds the fact.
 *
 * <p>Contrast {@link ObjectStoreUnavailableException}, which is not the creator's problem
 * and must not be recorded against their row.
 */
public class MediaFailedException extends RuntimeException {

    private final transient MediaFailureReason reason;

    public MediaFailedException(MediaFailureReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "A failure has a reason");
    }

    public MediaFailedException(MediaFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "A failure has a reason");
    }

    public MediaFailureReason reason() {
        return reason;
    }
}
