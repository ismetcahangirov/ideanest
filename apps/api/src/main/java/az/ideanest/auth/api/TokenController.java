package az.ideanest.auth.api;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AuthenticationFailedException;
import az.ideanest.auth.application.IssuedTokens;
import az.ideanest.auth.application.RefreshService;
import az.ideanest.auth.application.SignInService;
import az.ideanest.auth.application.SignInService.SignInCommand;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.ratelimit.RateLimitExceededException;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in, refresh, and sign-out. */
@RestController
@RequestMapping("/v1/auth")
public class TokenController {

    /**
     * Required on any request that authenticates with the refresh cookie.
     *
     * <p>{@code SameSite=Strict} already stops a cross-site request from
     * carrying the cookie, and this is the second lock §17.3 asks for: a header
     * this specific cannot be set by a form post or an image tag, only by
     * script, and script that can set it is already same-origin.
     */
    private static final String CLIENT_HEADER = "X-IdeaNest-Client";

    private final SignInService signIns;
    private final RefreshService refreshes;
    private final RefreshCookies cookies;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;

    public TokenController(
            SignInService signIns,
            RefreshService refreshes,
            RefreshCookies cookies,
            RateLimiter rateLimiter,
            AuthProperties properties) {
        this.signIns = signIns;
        this.refreshes = refreshes;
        this.cookies = cookies;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> signIn(
            @Valid @RequestBody SignInRequest request, HttpServletRequest httpRequest) {

        AuthProperties.RateLimit limits = properties.rateLimit();
        // §17.3: five attempts per address per fifteen minutes. Per email as
        // well, so that a distributed guess against one account is bounded too.
        enforce(rateLimiter.recordAttempt(
                "login:ip:" + clientAddressOf(httpRequest), limits.signInsPerAddress(), limits.window()));

        EmailAddress email = EmailAddress.of(request.email());
        enforce(rateLimiter.recordAttempt(
                "login:email:" + email.value(), limits.signInsPerEmail(), limits.window()));

        IssuedTokens tokens = signIns.signIn(new SignInCommand(
                email,
                request.password(),
                request.deviceLabel(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT),
                clientAddressOf(httpRequest)));

        return respondWith(tokens, request.wantsTokenInBody());
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Valid @RequestBody(required = false) RefreshRequest request, HttpServletRequest httpRequest) {

        PresentedToken presented = presentedTokenOf(request, httpRequest);
        IssuedTokens tokens;
        try {
            tokens = refreshes.refresh(presented.value());
        } catch (AuthenticationFailedException e) {
            // The session may well have just been revoked for reuse. Leaving a
            // dead cookie in the browser means every subsequent request carries
            // a credential that can only fail.
            HttpHeaders headers = new HttpHeaders();
            cookies.clear(headers);
            throw new AuthenticationFailedWithHeaders(e.getMessage(), headers);
        }

        return respondWith(tokens, presented.fromBody());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> signOut(
            @RequestBody(required = false) RefreshRequest request, HttpServletRequest httpRequest) {

        // Signing out is not something to refuse. A caller with no token at all
        // is already in the state they asked for.
        presentedTokenQuietly(request, httpRequest).ifPresent(token -> refreshes.signOut(token.value()));

        HttpHeaders headers = new HttpHeaders();
        cookies.clear(headers);
        return ResponseEntity.noContent().headers(headers).build();
    }

    /** Where the token came from, which decides where the new one goes back. */
    private record PresentedToken(String value, boolean fromBody) {
    }

    private PresentedToken presentedTokenOf(RefreshRequest request, HttpServletRequest httpRequest) {
        return presentedTokenQuietly(request, httpRequest)
                .orElseThrow(() -> new AuthenticationFailedException("This session is no longer valid. Sign in again."));
    }

    private Optional<PresentedToken> presentedTokenQuietly(RefreshRequest request, HttpServletRequest httpRequest) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            return Optional.of(new PresentedToken(request.refreshToken(), true));
        }
        return cookies.readFrom(httpRequest).map(value -> {
            if (httpRequest.getHeader(CLIENT_HEADER) == null) {
                throw new CookieClientHeaderMissingException();
            }
            return new PresentedToken(value, false);
        });
    }

    private ResponseEntity<TokenResponse> respondWith(IssuedTokens tokens, boolean tokenInBody) {
        HttpHeaders headers = new HttpHeaders();
        // The cookie is set either way. A native client that asked for the body
        // simply has no cookie jar to put it in, and a browser that did not ask
        // gets the token only in a place its own scripts cannot read.
        cookies.set(headers, tokens.refreshToken(), Duration.between(Instant.now(), tokens.refreshTokenExpiresAt()));

        long expiresIn = Duration.between(Instant.now(), tokens.accessTokenExpiresAt()).toSeconds();
        TokenResponse body =
                TokenResponse.of(tokens.accessToken(), expiresIn, tokenInBody ? tokens.refreshToken() : null);

        return ResponseEntity.ok()
                .headers(headers)
                // Tokens must not be cached anywhere, by anything.
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static void enforce(RateLimitDecision decision) {
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }

    private static String clientAddressOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }

    /** A refusal that also has to clear the cookie. */
    static class AuthenticationFailedWithHeaders extends AuthenticationFailedException {

        private static final long serialVersionUID = 1L;

        private final transient HttpHeaders headers;

        AuthenticationFailedWithHeaders(String message, HttpHeaders headers) {
            super(message);
            this.headers = headers;
        }

        HttpHeaders headers() {
            return headers;
        }
    }

    /** A cookie-authenticated request without the header that §17.3 requires. */
    static class CookieClientHeaderMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CookieClientHeaderMissingException() {
            super("This request must carry the " + CLIENT_HEADER + " header.");
        }
    }
}
