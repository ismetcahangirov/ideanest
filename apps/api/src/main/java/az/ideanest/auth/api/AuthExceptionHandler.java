package az.ideanest.auth.api;

import az.ideanest.auth.application.VerificationRejectedException;
import az.ideanest.auth.application.WeakPasswordException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

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
