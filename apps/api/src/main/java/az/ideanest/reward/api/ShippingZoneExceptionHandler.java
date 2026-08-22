package az.ideanest.reward.api;

import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.reward.application.ShippingZoneInvalidException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The shipping-zone endpoints' failures, as RFC 9457 problem details.
 *
 * <p>Its own advice rather than three more methods on {@code RewardExceptionHandler},
 * for the reason that file gives about sibling issues: an advice is scoped by
 * {@code assignableTypes}, and adding a controller to somebody else's list is the one
 * line every branch in flight conflicts in.
 *
 * <p>The two refusals it shares with the rest of the module — no such campaign, and a
 * grant that does not include {@code EDIT_REWARDS} — answer with the same bodies and
 * the same codes. A client that already handles {@code PROJECT_NOT_FOUND} should not
 * need a second branch because it called a different endpoint.
 */
@RestControllerAdvice(assignableTypes = ShippingZoneController.class)
public class ShippingZoneExceptionHandler {

    /**
     * 400 for a set of regions the platform will not store.
     *
     * <p>A 400 rather than a 409: nothing has changed underneath the caller, the body
     * simply does not describe a set of regions. Every case is one V37 also refuses —
     * see {@code ShippingZoneInvalidException} — and the detail is the sentence the
     * rate editor puts beside the field.
     */
    @ExceptionHandler(ShippingZoneInvalidException.class)
    public ProblemDetail handleInvalidZones(ShippingZoneInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/shipping-zone-invalid"));
        problem.setTitle("Invalid shipping regions");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "SHIPPING_ZONE_INVALID");
        problem.setProperty("meta", Map.of("field", "zones"));
        return problem;
    }

    /**
     * 404 for a campaign that does not exist, and for one this caller has no part in.
     *
     * <p>Deliberately the same answer, per {@code ProjectAuthorisation}: a caller who
     * is not party to a campaign is not told that it exists.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That campaign does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /** 403 for a collaborator whose grant does not include {@code EDIT_REWARDS}. */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("You do not have permission to edit this campaign's rewards.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }
}
