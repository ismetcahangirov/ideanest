package az.ideanest.project.api;

import az.ideanest.project.application.InvalidProfileCursorException;
import az.ideanest.user.application.ProfileNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link ProfileProjectController}'s two refusals, as RFC 9457 problem details (§10.4).
 *
 * <p>Its own advice rather than two types added to {@link ProjectExceptionHandler}, which
 * is the argument {@code PublicBackerExceptionHandler} already makes: that file translates
 * ten of this module's failures for six controllers, none of which is a profile, and
 * neither exception here is one of its ten. It is also honest about the shape — the
 * campaign editor has ten ways to fail and a public list has two, because it validates
 * nothing, locks nothing and writes nothing.
 *
 * <p><strong>Neither may escape unhandled.</strong> This is a {@code permitAll} endpoint,
 * and an unhandled exception on one reaches Spring Security's error dispatch and comes back
 * as 401 — which would tell an anonymous visitor to sign in to see a profile that does not
 * exist, and would answer a token-holder differently from a stranger for the same private
 * profile. {@code ProjectExceptionHandler} names the same trap about
 * {@code PublicProjectController}.
 */
@RestControllerAdvice(assignableTypes = ProfileProjectController.class)
public class ProfileProjectExceptionHandler {

    /**
     * 404 for a slug nobody holds, for an account §17.4 has anonymised, and for one whose
     * owner chose {@code PRIVATE}.
     *
     * <p>The same body and the same {@code USER_NOT_FOUND} code the user module's own
     * advice answers {@code GET /v1/users/{slug}} with, and that the community module
     * answers {@code POST /v1/users/{slug}/follow} with. Three endpoints, one fact, one
     * answer: a client handling it needs no second branch, and — more to the point — the
     * profile and its two tabs must not be distinguishable from each other by the shape of
     * their refusals, or the difference between the answers is the oracle none of them is.
     *
     * <p><strong>Not a 403.</strong> This endpoint takes no credential, so a 403 would be
     * an oracle any stranger could ask about somebody who asked this platform for no page.
     */
    @ExceptionHandler(ProfileNotFoundException.class)
    public ProblemDetail handleProfileNotFound(ProfileNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/user-not-found"));
        problem.setTitle("No such account");
        problem.setDetail("There is no account at that address.");
        problem.setProperty("code", "USER_NOT_FOUND");
        return problem;
    }

    /**
     * 400 for a page cursor this endpoint did not issue.
     *
     * <p>The body does not echo the value and does not say which part of it was wrong — see
     * {@link InvalidProfileCursorException}. The client's move is the same in every case:
     * ask for the first page. The same code {@code BackerSignalExceptionHandler} uses, for
     * the reason above.
     */
    @ExceptionHandler(InvalidProfileCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidProfileCursorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-cursor"));
        problem.setTitle("Invalid cursor");
        problem.setDetail("That page cursor is not one this endpoint issued.");
        problem.setProperty("code", "INVALID_CURSOR");
        return problem;
    }
}
