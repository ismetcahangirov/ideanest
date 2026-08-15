package az.ideanest.auth.api;

import az.ideanest.auth.application.AccountLinkRefusedException;
import az.ideanest.auth.application.AuthenticationFailedException;
import az.ideanest.auth.application.TwoFactorRejectedException;
import az.ideanest.auth.application.ProviderNotConfiguredException;
import az.ideanest.auth.application.VerificationRejectedException;
import az.ideanest.auth.application.WeakPasswordException;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Auth's own failures, as problem details.
 *
 * <p>Scoped to {@link AuthController} rather than applied globally: mapping
 * something as broad as {@code IllegalArgumentException} to 400 across the
 * whole service would turn a genuine bug somewhere else into a cheerful "bad
 * request" and hide it.
 */
@RestControllerAdvice(
        assignableTypes = {AuthController.class, TokenController.class, TwoFactorController.class})
public class AuthExceptionHandler {

    /**
     * Every way of failing to authenticate, with one status and one message.
     *
     * <p>401 rather than 403: the caller has not proved who they are, and a
     * client is expected to react by signing in rather than by giving up.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create("https://ideanest.az/problems/authentication-failed"));
        problem.setTitle("Not authenticated");
        problem.setDetail(exception.getMessage());

        HttpHeaders headers = exception instanceof TokenController.AuthenticationFailedWithHeaders withHeaders
                ? withHeaders.headers()
                : new HttpHeaders();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .headers(headers)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    /**
     * A cookie-authenticated request that did not carry the client header.
     *
     * <p>403 rather than 401: the credential was there and was refused on a
     * different ground, and retrying the same request will not help.
     */
    @ExceptionHandler(TokenController.CookieClientHeaderMissingException.class)
    public ProblemDetail handleMissingClientHeader(TokenController.CookieClientHeaderMissingException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/missing-client-header"));
        problem.setTitle("Request refused");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A provider sign-in that proved an address which already belongs to an
     * account here, in a state where linking the two would not be safe.
     *
     * <p>409 rather than 401: the credentials were fine and the conflict is with
     * the state of an account, which retrying identically will not change. The
     * message says what to do about it, and it can afford to — the caller has
     * just proven to the provider that this address is theirs, so it tells them
     * nothing they could not learn by asking for a password reset.
     */
    @ExceptionHandler(AccountLinkRefusedException.class)
    public ProblemDetail handleAccountLinkRefused(AccountLinkRefusedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/account-link-refused"));
        problem.setTitle("Sign-in refused");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A provider this build supports and this environment has no credentials for.
     *
     * <p>501 rather than 404: the endpoint exists, the provider is one we know,
     * and what is missing is on our side. A client told this can say "not
     * available here" instead of showing a button that fails as though the user
     * did something wrong.
     */
    @ExceptionHandler(ProviderNotConfiguredException.class)
    public ProblemDetail handleProviderNotConfigured(ProviderNotConfiguredException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_IMPLEMENTED);
        problem.setType(URI.create("https://ideanest.az/problems/provider-not-configured"));
        problem.setTitle("Provider not available");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /** A provider segment naming something this service has never heard of. */
    @ExceptionHandler(TokenController.UnknownProviderException.class)
    public ProblemDetail handleUnknownProvider(TokenController.UnknownProviderException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/unknown-provider"));
        problem.setTitle("No such provider");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ProblemDetail handleWeakPassword(WeakPasswordException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/weak-password"));
        problem.setTitle("Password rejected");
        // The policy's own words. A user cannot fix a password they are not
        // told the requirement for.
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A two-factor change that was refused.
     *
     * <p>400 rather than 401: the caller is authenticated and their token is
     * fine. What failed is the password or the code they offered with it, and
     * the message does not say which — somebody using a stolen access token to
     * switch two-factor off should not be told how close they got.
     */
    @ExceptionHandler(TwoFactorRejectedException.class)
    public ResponseEntity<ProblemDetail> handleTwoFactorRejected(TwoFactorRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/two-factor-rejected"));
        problem.setTitle("Two-factor change refused");
        problem.setDetail(exception.getMessage());

        return ResponseEntity.badRequest()
                // These responses are about credentials. Nothing caches them.
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(VerificationRejectedException.class)
    public ProblemDetail handleVerificationRejected(VerificationRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-verification-link"));
        problem.setTitle("Verification failed");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * {@link az.ideanest.shared.EmailAddress} rejects what is not an address.
     * The request type validates the same thing, so reaching here means a shape
     * the annotation accepted and the value object did not — still the client's
     * problem, not ours.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-request"));
        problem.setTitle("Invalid request");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}
