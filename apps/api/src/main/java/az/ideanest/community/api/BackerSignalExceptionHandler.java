package az.ideanest.community.api;

import az.ideanest.community.application.CannotFollowYourselfException;
import az.ideanest.community.application.FollowTargetNotFoundException;
import az.ideanest.community.application.InvalidSignalCursorException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What the saving and following endpoints refuse, as RFC 9457 problem details (§10.4).
 *
 * <p>Scoped to {@link BackerSignalController} rather than declared globally, for
 * {@code CommentExceptionHandler}'s reason: a second translation of
 * {@code ProjectNotFoundException} in front of a controller that already has one is two bodies
 * for one refusal, and the drift shows up as the same failure answering differently depending
 * on which endpoint produced it.
 */
@RestControllerAdvice(assignableTypes = BackerSignalController.class)
public class BackerSignalExceptionHandler {

    /**
     * 404 for a campaign that does not exist and for one the public may not see.
     *
     * <p>The same answer for both, as everywhere else: a draft is an unreleased product, and an
     * endpoint that distinguished "not there" from "not yet public" would report on what other
     * people are preparing to whoever tried to save it.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That project does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /**
     * 404 for an account that does not exist and for one that has been closed.
     *
     * <p>One answer for both, which §17.4 requires rather than merely permits: an endpoint that
     * distinguished them would confirm that a particular person used to have an account here,
     * to anybody who could guess their slug.
     */
    @ExceptionHandler(FollowTargetNotFoundException.class)
    public ProblemDetail handleTargetNotFound(FollowTargetNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/user-not-found"));
        problem.setTitle("No such account");
        problem.setDetail("There is no account at that address.");
        problem.setProperty("code", "USER_NOT_FOUND");
        return problem;
    }

    /**
     * 422 for following yourself.
     *
     * <p>Not a 400: the request is well formed and the slug names a real account. What is wrong
     * is the relationship it asks for, which is what 422 is for.
     */
    @ExceptionHandler(CannotFollowYourselfException.class)
    public ProblemDetail handleSelfFollow(CannotFollowYourselfException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/cannot-follow-yourself"));
        problem.setTitle("Cannot follow yourself");
        problem.setDetail("An account cannot follow itself.");
        problem.setProperty("code", "CANNOT_FOLLOW_YOURSELF");
        return problem;
    }

    /**
     * 400 for a page cursor this endpoint did not issue.
     *
     * <p>The body does not echo the value and does not say which part of it was wrong — see
     * {@link InvalidSignalCursorException}. The client's move is the same in every case: ask
     * for the first page.
     */
    @ExceptionHandler(InvalidSignalCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidSignalCursorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-cursor"));
        problem.setTitle("Invalid cursor");
        problem.setDetail("That page cursor is not one this endpoint issued.");
        problem.setProperty("code", "INVALID_CURSOR");
        return problem;
    }
}
