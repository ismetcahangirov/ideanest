package az.ideanest.project.api;

import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.CollaboratorAlreadyInvitedException;
import az.ideanest.project.application.CollaboratorNotFoundException;
import az.ideanest.project.application.InvitationRejectedException;
import az.ideanest.project.application.ProjectFieldRejectedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The collaborator endpoints' failures, as RFC 9457 problem details.
 *
 * <p>Its own advice rather than an addition to {@link ProjectExceptionHandler}'s
 * list, for two reasons. The listed types are the file three sibling issues would
 * each edit on the same line; and these controllers have failures of their own —
 * an invitation that cannot be accepted, an address already invited — which the
 * campaign endpoints have no way to raise.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4. The
 * status tells a client how to behave; the code tells it what happened, and it is
 * what a client branches on.
 */
@RestControllerAdvice(
        assignableTypes = {ProjectCollaboratorController.class, CollaboratorController.class})
public class CollaboratorExceptionHandler {

    /**
     * 404 for a campaign that does not exist, for one this caller may not see, and
     * for one whose grant on this caller was revoked.
     *
     * <p>The same answer in all three cases, deliberately. A draft is confidential,
     * and a revocation withdraws exactly the access that made it visible — so
     * telling a revoked collaborator that the campaign is still there, and merely
     * closed to them, would leak what they were invited into and then removed from.
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

    /** 404 for a grant identifier that names nothing this caller may manage. */
    @ExceptionHandler(CollaboratorNotFoundException.class)
    public ProblemDetail handleCollaboratorNotFound(CollaboratorNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/collaborator-not-found"));
        problem.setTitle("No such collaborator");
        problem.setDetail("That collaborator does not exist.");
        problem.setProperty("code", "COLLABORATOR_NOT_FOUND");
        return problem;
    }

    /**
     * 403 for a caller who works on the campaign and is missing the capability this
     * request needs — including a manager trying to confer more than they hold.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        return ProjectProblems.capabilityNotGranted(exception);
    }

    /**
     * 409 for an address that already has a live invitation or an active grant.
     *
     * <p>Not a 400: the request is well formed, and what refuses it is the state of
     * the campaign's team — which frequently is the state another manager has just
     * put it in.
     */
    @ExceptionHandler(CollaboratorAlreadyInvitedException.class)
    public ProblemDetail handleAlreadyInvited(CollaboratorAlreadyInvitedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/collaborator-already-invited"));
        problem.setTitle("Already invited");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "COLLABORATOR_ALREADY_INVITED");
        return problem;
    }

    /**
     * 409 for an invitation that cannot be accepted, or a grant that has been
     * withdrawn.
     *
     * <p>The message is the response, because it is the only thing that tells the
     * two ordinary cases apart: a link that expired last week and a grant a creator
     * withdrew an hour ago. An unknown token is deliberately indistinguishable from
     * one belonging to a campaign the caller cannot see.
     */
    @ExceptionHandler(InvitationRejectedException.class)
    public ProblemDetail handleInvitationRejected(InvitationRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/invitation-rejected"));
        problem.setTitle("Invitation cannot be used");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "INVITATION_REJECTED");
        return problem;
    }

    /**
     * 400 for one field of the request.
     *
     * <p>Reused from the campaign endpoints rather than given a collaborator-specific
     * type: "this field is wrong, and here is which" is the same answer whether the
     * field is a funding goal or a capability list, and the editor puts the message
     * beside the input either way.
     */
    @ExceptionHandler(ProjectFieldRejectedException.class)
    public ProblemDetail handleFieldRejected(ProjectFieldRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/project-field-invalid"));
        problem.setTitle("Invalid field");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "PROJECT_FIELD_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field()));
        return problem;
    }

    /**
     * {@link az.ideanest.shared.EmailAddress} rejects what is not an address, and
     * {@code Collaborator} rejects a grant that confers nothing. The request types
     * validate both, so reaching here means input the binding accepted and the value
     * object did not — still the client's problem.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-request"));
        problem.setTitle("Invalid request");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }
}
