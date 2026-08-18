package az.ideanest.auth.api;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.AuthenticationFailedException;
import az.ideanest.auth.application.IssuedTokens;
import az.ideanest.auth.application.RefreshService;
import az.ideanest.auth.application.SignInOutcome;
import az.ideanest.auth.application.SignInService;
import az.ideanest.auth.application.SignInService.SignInCommand;
import az.ideanest.auth.application.SocialSignInService;
import az.ideanest.auth.application.SocialSignInService.SocialSignInCommand;
import az.ideanest.auth.domain.IdentityProvider;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.ratelimit.ClientAddress;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final SocialSignInService socialSignIns;
    private final RefreshService refreshes;
    private final RefreshCookies cookies;
    private final TokenResponses responses;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;

    public TokenController(
            SignInService signIns,
            SocialSignInService socialSignIns,
            RefreshService refreshes,
            RefreshCookies cookies,
            TokenResponses responses,
            RateLimiter rateLimiter,
            AuthProperties properties) {
        this.signIns = signIns;
        this.socialSignIns = socialSignIns;
        this.refreshes = refreshes;
        this.cookies = cookies;
        this.responses = responses;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Signs in, or asks for the second factor.
     *
     * <p>Two shapes come back from this one endpoint, which is why the type is
     * open. With two-factor off it is a {@link TokenResponse}. With two-factor
     * on it is a {@link TwoFactorChallengeResponse} — {@code twoFactorRequired}
     * true, a challenge, and no tokens at all — and the client sends the
     * challenge and a code to {@code /v1/auth/2fa/verify}.
     *
     * <p>200 rather than 401 for the second case: nothing was refused. The
     * password was accepted and the flow is halfway through, and a client that
     * treats it as a failure would be reacting to the wrong thing. A client
     * that ignores the field gets no access token, which fails at its next
     * request rather than quietly signing anybody in.
     */
    @PostMapping("/login")
    public ResponseEntity<?> signIn(@Valid @RequestBody SignInRequest request, HttpServletRequest httpRequest) {

        AuthProperties.RateLimit limits = properties.rateLimit();
        // §17.3: five attempts per address per fifteen minutes. Per email as
        // well, so that a distributed guess against one account is bounded too.
        RateLimits.enforce(rateLimiter.recordAttempt(
                "login:ip:" + ClientAddress.of(httpRequest), limits.signInsPerAddress(), limits.window()));

        EmailAddress email = EmailAddress.of(request.email());
        RateLimits.enforce(rateLimiter.recordAttempt(
                "login:email:" + email.value(), limits.signInsPerEmail(), limits.window()));

        SignInOutcome outcome = signIns.signIn(new SignInCommand(
                email,
                request.password(),
                request.deviceLabel(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT),
                ClientAddress.of(httpRequest)));

        return respondTo(outcome, request.wantsTokenInBody());
    }

    /**
     * Turns a sign-in outcome into a response.
     *
     * <p>A switch over a sealed type rather than an if: a third outcome — an SMS
     * factor, a forced password change — then fails to compile here instead of
     * falling through to "signed in". Shared by the password and the provider
     * paths so that neither can grow a different answer to the same question.
     */
    private ResponseEntity<?> respondTo(SignInOutcome outcome, boolean tokenInBody) {
        return switch (outcome) {
            case SignInOutcome.TwoFactorRequired challenge -> ResponseEntity.ok()
                    // A challenge is a credential for the next five minutes.
                    .cacheControl(CacheControl.noStore())
                    .body(TwoFactorChallengeResponse.of(
                            challenge.challenge(),
                            Duration.between(Instant.now(), challenge.expiresAt()).toSeconds()));
            case SignInOutcome.Authenticated authenticated -> responses.of(authenticated.tokens(), tokenInBody);
        };
    }

    /**
     * Signs in with a Google or Apple ID token.
     *
     * <p>Ends in the same place as a password sign-in — the same session, the
     * same rotation, the same fifteen-minute access token — because the two
     * differ only in how the person was identified. Including the second factor:
     * an account with two-factor confirmed gets a challenge here as well.
     * Letting a provider button skip it would make two-factor advisory, which is
     * the same as not having it.
     */
    @PostMapping("/oauth/{provider}")
    public ResponseEntity<?> signInWithProvider(
            @PathVariable String provider,
            @Valid @RequestBody OAuthSignInRequest request,
            HttpServletRequest httpRequest) {

        IdentityProvider identityProvider =
                IdentityProvider.parse(provider).orElseThrow(UnknownProviderException::new);

        AuthProperties.RateLimit limits = properties.rateLimit();
        // Not a guessing defence — an ID token cannot be guessed. It bounds what
        // one caller can make us spend on signature verification and on fetching
        // a rotated key set from the provider.
        RateLimits.enforce(rateLimiter.recordAttempt(
                "oauth:ip:" + ClientAddress.of(httpRequest), limits.socialSignInsPerAddress(), limits.window()));

        SignInOutcome outcome = socialSignIns.signIn(new SocialSignInCommand(
                identityProvider,
                request.idToken(),
                request.nonce(),
                request.name(),
                request.localeOrDefault(),
                request.deviceLabel(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT),
                ClientAddress.of(httpRequest)));

        return respondTo(outcome, request.wantsTokenInBody());
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

        return responses.of(tokens, presented.fromBody());
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

    /** A provider segment that names nothing this service knows about. */
    static class UnknownProviderException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownProviderException() {
            // The segment the caller sent is not echoed back. Reflecting input
            // into a response body is a habit worth not having, and the caller
            // already knows what they asked for.
            super("There is no sign-in provider at that address.");
        }
    }
}
