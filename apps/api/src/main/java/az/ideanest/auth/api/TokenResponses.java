package az.ideanest.auth.api;

import az.ideanest.auth.application.IssuedTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The one place a pair of tokens becomes a response.
 *
 * <p>Two endpoints now hand out sessions — a password on its own, and a
 * password followed by a code — and both have to make the same three decisions:
 * set the cookie whatever the client asked for, return the refresh token in the
 * body only when it did ask, and forbid caching. Writing that twice is how one
 * of the two ends up cacheable.
 */
@Component
public class TokenResponses {

    private final RefreshCookies cookies;
    private final Clock clock;

    public TokenResponses(RefreshCookies cookies, Clock clock) {
        this.cookies = cookies;
        this.clock = clock;
    }

    public ResponseEntity<TokenResponse> of(IssuedTokens tokens, boolean tokenInBody) {
        Instant now = clock.instant();

        HttpHeaders headers = new HttpHeaders();
        // The cookie is set either way. A native client that asked for the body
        // simply has no cookie jar to put it in, and a browser that did not ask
        // gets the token only in a place its own scripts cannot read.
        cookies.set(headers, tokens.refreshToken(), Duration.between(now, tokens.refreshTokenExpiresAt()));

        long expiresIn = Duration.between(now, tokens.accessTokenExpiresAt()).toSeconds();
        TokenResponse body =
                TokenResponse.of(tokens.accessToken(), expiresIn, tokenInBody ? tokens.refreshToken() : null);

        return ResponseEntity.ok()
                .headers(headers)
                // Tokens must not be cached anywhere, by anything.
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
