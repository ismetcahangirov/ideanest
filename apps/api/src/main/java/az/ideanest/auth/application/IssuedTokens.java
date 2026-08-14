package az.ideanest.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * What a sign-in or a refresh hands back.
 *
 * <p>The refresh token value appears here and in the response, and never again:
 * what is stored is its hash.
 */
public record IssuedTokens(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID sessionId) {
}
