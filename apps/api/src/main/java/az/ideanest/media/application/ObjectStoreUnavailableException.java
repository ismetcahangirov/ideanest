package az.ideanest.media.application;

/**
 * The object store could not be reached — the media pipeline design of 2026-08-30.
 *
 * <p><strong>Deliberately not a {@link MediaFailedException}.</strong> The distinction is
 * the whole reason this type exists: a bucket that is unreachable is not something a
 * creator's file did, and recording it against their upload would tell them to send a
 * different photograph to fix somebody else's outage.
 *
 * <p>So a pass of the sweep that hits this leaves the row claimed and throws, which is how
 * {@code ScheduledJob} reports a failed run — the runner counts the attempt, releases the
 * lease and backs off, and the next pass finds the row exactly where it was.
 */
public class ObjectStoreUnavailableException extends RuntimeException {

    public ObjectStoreUnavailableException(String message) {
        super(message);
    }

    public ObjectStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
