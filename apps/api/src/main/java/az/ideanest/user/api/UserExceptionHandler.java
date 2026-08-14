package az.ideanest.user.api;

import az.ideanest.user.application.IncorrectPasswordException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The user module's own failures, as problem details.
 *
 * <p>Scoped to this module's controllers rather than applied globally, for the
 * same reason {@code AuthExceptionHandler} is: an advice that catches a broad
 * type across the whole service turns a bug somewhere else into a tidy 4xx and
 * hides it.
 */
@RestControllerAdvice(
        assignableTypes = {AccountDeletionController.class, AccountExportController.class, MeController.class})
public class UserExceptionHandler {

    /**
     * 403, not 401. The access token was accepted; what failed is the password
     * that closing an account additionally requires. A 401 would tell the client
     * to sign in again, which is not the problem and would lose the request.
     */
    @ExceptionHandler(IncorrectPasswordException.class)
    public ProblemDetail handleIncorrectPassword(IncorrectPasswordException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/incorrect-password"));
        problem.setTitle("Password required");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}
