package az.ideanest.media.application;

/**
 * This deployment has no object storage configured — the media pipeline design of
 * 2026-08-30.
 *
 * <p>The same position {@code DocumentStorageUnavailableException} takes about encryption
 * keys, for the same reason. There is no default bucket, because a default would be a guess
 * about somebody else's infrastructure; a deployment that has not configured one starts
 * normally, serves every other endpoint, and answers here with a 503 that says uploads are
 * not available rather than one that says something went wrong.
 */
public class UploadsUnavailableException extends RuntimeException {

    public UploadsUnavailableException(String message) {
        super(message);
    }
}
