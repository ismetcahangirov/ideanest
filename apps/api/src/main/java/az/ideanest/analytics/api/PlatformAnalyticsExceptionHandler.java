package az.ideanest.analytics.api;

import az.ideanest.analytics.application.InvalidAnalyticsRangeException;
import az.ideanest.staff.api.StaffRefusals;
import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AD-13's refusals — issue #313.
 *
 * <p>Separate from {@link AnalyticsExceptionHandler}, which is scoped to the campaign-level
 * endpoint. The two share {@link InvalidAnalyticsRangeException} because a reporting window
 * is the same idea on both, and they are scoped separately because the staff refusals are
 * meaningless on a creator's own analytics — an advice covering refusals only some of its
 * controllers can raise is one that has to be read to rule out.
 */
@RestControllerAdvice(assignableTypes = PlatformAnalyticsController.class)
public class PlatformAnalyticsExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }

    /**
     * 400 for a window that runs backwards or covers more than a year.
     *
     * <p>The service's own message, which names the limit. A refusal that said only
     * "invalid range" would send somebody bisecting dates to find the ceiling.
     */
    @ExceptionHandler(InvalidAnalyticsRangeException.class)
    public ProblemDetail handleRange(InvalidAnalyticsRangeException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-analytics-range"));
        problem.setTitle("That reporting window cannot be read");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "INVALID_ANALYTICS_RANGE");
        return problem;
    }
}
