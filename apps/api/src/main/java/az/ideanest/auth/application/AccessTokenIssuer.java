package az.ideanest.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Minting the short-lived token a client sends on every request.
 *
 * <p>An interface so that the fact it is a JWT stays in one class. Everything
 * else in the module deals in "an access token and when it expires".
 */
public interface AccessTokenIssuer {

    /**
     * @param userId becomes {@code sub}
     * @param sessionId becomes {@code sid}. It is what makes an access token
     *     traceable to the sign-in that produced it, which matters when a
     *     session is revoked and somebody asks which requests it made
     * @param emailVerified becomes a claim, so that an endpoint requiring a
     *     verified address does not need a database read to find out
     */
    IssuedAccessToken issue(UUID userId, UUID sessionId, boolean emailVerified, Instant now);

    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
