package az.ideanest.obligation.api;

import az.ideanest.obligation.application.NoLapseToResolveException;
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
 * What the obligation endpoints refuse with — RFC 9457, as everything here is.
 *
 * <p>Scoped to the admin controller rather than global, for the reason every advice in this
 * service gives. The public controller is not listed because it refuses nothing: a campaign with
 * no clock answers 204, which is a state and not an error.
 */
@RestControllerAdvice(assignableTypes = AdminObligationController.class)
public class ObligationExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }

    /**
     * <strong>409: there is nothing here to resolve.</strong>
     *
     * <p>409 rather than 404, and one code for three situations — no obligation, no lapse, and a
     * lapse a colleague closed a moment ago. They are the same to the moderator looking at the
     * screen and the recovery is identical: reload the queue. Three codes in front of one button
     * would be three messages that all mean "it is not there any more".
     */
    @ExceptionHandler(NoLapseToResolveException.class)
    public ProblemDetail handleNoLapse(NoLapseToResolveException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/no-lapse-to-resolve"));
        problem.setTitle("There is no open escalation on this campaign");
        problem.setDetail("It may have been resolved by somebody else, or the creator may have posted. Reload the queue.");
        problem.setProperty("code", "NO_LAPSE_TO_RESOLVE");
        problem.setProperty("meta", Map.of("project", exception.projectId().toString()));
        return problem;
    }
}
