package az.ideanest.compliance.api;

import az.ideanest.compliance.application.InvalidOverrideWindowException;
import az.ideanest.compliance.application.UnknownOverrideException;
import az.ideanest.compliance.domain.SelfGrantedOverrideException;
import az.ideanest.staff.api.StaffRefusals;
import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What the override endpoints refuse with — RFC 9457, as everything here is.
 *
 * <p>Scoped to the one controller rather than global, for the reason every advice in this
 * service gives: an advice that catches a broad exception everywhere turns a bug three modules
 * away into a 4xx that looks like the caller's fault.
 *
 * <p><strong>Every refusal carries a {@code code}.</strong> The console branches on it — a
 * client parsing a human sentence to decide what to draw is a client that breaks when the
 * sentence is translated, and §21.1 has four languages.
 */
@RestControllerAdvice(assignableTypes = ComplianceOverrideController.class)
public class ComplianceExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }

    /**
     * <strong>403: an account may not waive a rule for itself.</strong>
     *
     * <p>403 rather than 400, and the distinction is the usual one: the request is
     * well-formed and a different account could make it. It is the caller who is refused,
     * which is what 403 means.
     *
     * <p>Reached only when the token's subject is the path's account. The database refuses it
     * again — V66's constraint is the guarantee — and this is the readable half.
     */
    @ExceptionHandler(SelfGrantedOverrideException.class)
    public ProblemDetail handleSelfGrant(SelfGrantedOverrideException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/self-granted-override"));
        problem.setTitle("Nobody overrides a rule for their own account");
        problem.setDetail("Ask a colleague who holds the capability to grant this one.");
        problem.setProperty("code", "SELF_GRANTED_OVERRIDE");
        problem.setProperty(
                "meta",
                Map.of(
                        "requirement", exception.requirement().name(),
                        "account", exception.subjectUserId().toString()));
        return problem;
    }

    /**
     * <strong>400: the window is in the past or longer than the ceiling.</strong>
     *
     * <p>400 because it is the request's fault and a different date would be accepted, and
     * {@code meta.longestSeconds} so that the screen can say what would have been — an error
     * that says "no" without saying "up to ninety days" is one the operator answers by
     * guessing.
     */
    @ExceptionHandler(InvalidOverrideWindowException.class)
    public ProblemDetail handleWindow(InvalidOverrideWindowException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-override-window"));
        problem.setTitle("That is not a window an override may have");
        problem.setDetail("An override ends in the future, and no later than the ceiling below.");
        problem.setProperty("code", "INVALID_OVERRIDE_WINDOW");
        problem.setProperty(
                "meta",
                Map.of(
                        "expiresAt", exception.expiresAt().toString(),
                        "now", exception.now().toString(),
                        "longestSeconds", exception.longest().toSeconds()));
        return problem;
    }

    /** <strong>404: no such override.</strong> The console's cue to reload the account. */
    @ExceptionHandler(UnknownOverrideException.class)
    public ProblemDetail handleUnknown(UnknownOverrideException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/unknown-override"));
        problem.setTitle("There is no such override");
        problem.setDetail("It may have been withdrawn by somebody else. Reload the account.");
        problem.setProperty("code", "UNKNOWN_OVERRIDE");
        problem.setProperty("meta", Map.of("override", exception.overrideId().toString()));
        return problem;
    }
}
