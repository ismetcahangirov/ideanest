package az.ideanest.analytics.api;

import az.ideanest.analytics.application.InvalidAnalyticsRangeException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The analytics read's failures, as RFC 9457 problem details.
 *
 * <p>Scoped to {@link AnalyticsController} rather than applied globally, for the reason
 * {@code ReferralExceptionHandler} gives: an advice that catches a broad type across the
 * whole service turns a bug somewhere else into a tidy 4xx and hides it.
 *
 * <p><strong>And narrower than that one on purpose.</strong> It catches
 * {@link IllegalArgumentException}; this catches
 * {@link InvalidAnalyticsRangeException} only. {@code CurrencyMismatchException} is also
 * an {@code IllegalArgumentException}, its own comment says it must surface as a 500
 * because a client cannot fix it by sending something else, and a supertype catch here
 * would quietly demote it to a bad request.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4.
 */
@RestControllerAdvice(assignableTypes = AnalyticsController.class)
public class AnalyticsExceptionHandler {

    /**
     * 404 for a campaign that does not exist and for one this caller has no part in,
     * identically.
     *
     * <p>The same answer, body and code the referral report gives, and for the same extra
     * reason: what a campaign is raising and how fast is competitive information, so a
     * 403 would confirm the campaign exists to somebody enumerating identifiers.
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
     * 403 for a collaborator who is party to the campaign and holds no editing
     * capability.
     *
     * <p>Not a 404: they were invited, they can already see the campaign, and there is
     * nothing left to hide from them.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("You do not have the capability this request needs on that project.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }

    /**
     * 400 for a range that runs backwards or covers more than a year.
     *
     * <p>The detail is the exception's message, which names which of the two rules was
     * broken and is written for a person. Safe here because the only thing that raises
     * this type is the query string the caller sent.
     */
    @ExceptionHandler(InvalidAnalyticsRangeException.class)
    public ProblemDetail handleInvalidRange(InvalidAnalyticsRangeException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/analytics-range-invalid"));
        problem.setTitle("That is not a range this report answers");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "ANALYTICS_RANGE_INVALID");
        return problem;
    }
}
