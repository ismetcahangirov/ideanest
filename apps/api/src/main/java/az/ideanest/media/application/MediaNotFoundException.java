package az.ideanest.media.application;

import java.util.UUID;

/**
 * No such upload, or not this caller's — the media pipeline design of 2026-08-30.
 *
 * <p>One exception for both, deliberately, and the handler answers 404 to each. Telling
 * them apart would let anybody holding a token confirm that a given identifier exists,
 * which is the enumeration every other read on this platform already refuses.
 */
public class MediaNotFoundException extends RuntimeException {

    public MediaNotFoundException(UUID id) {
        super("No media " + id);
    }
}
