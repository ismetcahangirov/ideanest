package az.ideanest.analytics.api;

import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The analytics module's failures, as RFC 9457 problem details.
 *
 * <p>Scoped to this module's controller rather than applied globally, for the reason
 * {@code RewardExceptionHandler} gives: an advice that catches a broad type across the
 * whole service turns a bug somewhere else into a tidy 4xx and hides it. That matters
 * more than usual for {@link IllegalArgumentException} below, which is the broadest
 * type any advice in this service catches — and is confined to one controller with two
 * endpoints, where the only thing that raises it is a source the client described.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4.
 */
@RestControllerAdvice(assignableTypes = ReferralController.class)
public class ReferralExceptionHandler {

    /**
     * 404 for a campaign that does not exist, for one that is not publicly visible, and
     * for one this caller has no part in.
     *
     * <p>All three, identically, and the same body and code the project module uses. On
     * the capture endpoint it is what stops an unauthenticated write from being an
     * oracle for which identifiers exist; on the report it is
     * {@code ProjectAccess}' standing answer to a stranger, which matters here because
     * which sources brought a campaign its money is competitive information.
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
     * nothing left to hide from them — {@code ProjectAccess} draws that line and this
     * only reports it.
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
     * 400 for a source the value object refused: a direct visit that also named a
     * place, a non-direct one that named none, a referral link with no code, a label
     * past sixty-four characters.
     *
     * <p><strong>Caught rather than left to surface as a 500</strong>, because every
     * one of those is a client that can correct itself — the same argument
     * {@code MoneyDeserializer} makes for re-reporting the value object's own rules as
     * a bad request. The detail is the exception's message, which is written for a
     * person and names which rule was broken; that is safe here because the only thing
     * that raises this type on this controller is the client's own body.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidSource(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/referral-source-invalid"));
        problem.setTitle("That is not a referral source");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "REFERRAL_SOURCE_INVALID");
        return problem;
    }
}
