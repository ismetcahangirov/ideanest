package az.ideanest.auth.api;

import az.ideanest.auth.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The cookie a browser keeps its refresh token in.
 *
 * <p>Four attributes, each doing a job:
 *
 * <ul>
 *   <li><strong>HttpOnly</strong> — script cannot read it. This is what stops
 *       one cross-site scripting bug from becoming a thirty-day credential in
 *       an attacker's hands</li>
 *   <li><strong>Secure</strong> — never sent over plain HTTP, so it cannot be
 *       read off a café network. Off only on localhost, where the alternative
 *       is that it is never sent at all</li>
 *   <li><strong>SameSite=Strict</strong> — not attached to any cross-site
 *       request, which is what stops another origin from making the browser
 *       spend the token</li>
 *   <li><strong>Path</strong> — scoped to the auth endpoints, so it is not
 *       attached to every API call that has no use for it</li>
 * </ul>
 */
@Component
public class RefreshCookies {

    private final AuthProperties.RefreshCookie settings;

    public RefreshCookies(AuthProperties properties) {
        this.settings = properties.refreshCookie();
    }

    public void set(HttpHeaders headers, String token, Duration lifetime) {
        headers.add(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(settings.name(), token)
                        .httpOnly(true)
                        .secure(settings.secure())
                        .sameSite(settings.sameSite())
                        .path(settings.path())
                        .maxAge(lifetime)
                        .build()
                        .toString());
    }

    /** Expires the cookie. Sent on sign-out, and on any refusal that killed the session. */
    public void clear(HttpHeaders headers) {
        headers.add(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(settings.name(), "")
                        .httpOnly(true)
                        .secure(settings.secure())
                        .sameSite(settings.sameSite())
                        .path(settings.path())
                        .maxAge(Duration.ZERO)
                        .build()
                        .toString());
    }

    public Optional<String> readFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> settings.name().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
