package az.ideanest.shared.signature;

import java.time.Instant;
import java.util.Objects;

/**
 * A signing session that has been started — issue #429.
 *
 * <p>{@code verificationCode} is what the citizen compares against the code on their phone
 * before approving. It is shown to them and it is not a secret: the whole of its job is to be
 * read off two screens at once, and a session whose code the caller could not display would be
 * one the citizen approves without knowing what they are approving.
 *
 * @param sessionId the handle a later {@code resolve} names
 * @param verificationCode the code the citizen compares, or null if the provider issues none
 * @param expiresAt when the session stops being answerable
 */
public record SigningStarted(String sessionId, String verificationCode, Instant expiresAt) {

    public SigningStarted {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
