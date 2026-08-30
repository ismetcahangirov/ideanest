package az.ideanest.media.application;

/**
 * The transcoder is not installed, or would not run — the media pipeline design of
 * 2026-08-30.
 *
 * <p>The same distinction {@link ObjectStoreUnavailableException} draws, and it matters for
 * the same reason: a runtime image built without libvips is an operational fault, and
 * recording it against a creator's upload would tell them their photograph was the problem.
 * The row stays claimed, the pass throws, and the scheduler backs off.
 */
public class TranscoderUnavailableException extends RuntimeException {

    public TranscoderUnavailableException(String message) {
        super(message);
    }

    public TranscoderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
