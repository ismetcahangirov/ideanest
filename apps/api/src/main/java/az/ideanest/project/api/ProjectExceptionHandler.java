package az.ideanest.project.api;

import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.NotAModeratorException;
import az.ideanest.project.application.ProjectFieldRejectedException;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.ProjectNotLaunchableException;
import az.ideanest.project.application.ProjectTransitionNotAllowedException;
import az.ideanest.project.domain.ProjectState;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The project module's own failures, as RFC 9457 problem details.
 *
 * <p>Scoped to this module's controllers rather than applied globally, for the
 * reason {@code AuthExceptionHandler} and {@code UserExceptionHandler} give: an
 * advice that catches a broad type across the whole service turns a bug somewhere
 * else into a tidy 4xx and hides it.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4. The
 * status tells a client how to behave; the code tells it what happened, and it is
 * what a client branches on. Two different 409s that cannot be told apart would
 * force clients to match on prose.
 */
@RestControllerAdvice(assignableTypes = {ProjectController.class, ProjectModerationController.class})
public class ProjectExceptionHandler {

    /**
     * 404 for a campaign that does not exist, and for one this caller may not see.
     *
     * <p>Deliberately the same answer. A draft is confidential — an unreleased
     * product, a price nobody has been told — and distinguishing "not yours" from
     * "not there" turns the editor into an oracle for what other people are
     * preparing.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That project does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /**
     * 403 for a caller who is signed in and is not platform staff.
     *
     * <p>The one refusal in this module that is not a 404. See
     * {@link NotAModeratorException}: the endpoint is documented, the check happens
     * before any campaign is loaded, and an operator whose moderator list is
     * unconfigured needs to be told that rather than shown a missing endpoint.
     */
    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotAModerator(NotAModeratorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/not-a-moderator"));
        problem.setTitle("Not a moderator");
        // Deliberately not the exception's message, which names the account.
        problem.setDetail("Moderation decisions are taken by platform staff.");
        problem.setProperty("code", "NOT_A_MODERATOR");
        return problem;
    }

    /**
     * 403 for a collaborator who may work on this campaign and not in this way.
     *
     * <p>The second refusal in this module that is not a 404, and the reason
     * {@link ProjectNotFoundException} draws the distinction it does: the caller was
     * invited and can already read the campaign, so there is nothing left to hide
     * from them. What they are missing is a capability, and the response says which.
     *
     * <p>The body is built in {@link ProjectProblems} because the collaborator
     * endpoints raise the same failure, and one refusal should not have two bodies.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        return ProjectProblems.capabilityNotGranted(exception);
    }

    /**
     * 409 for a move §6.1 does not allow.
     *
     * <p>Not a 400: the request was well formed and would have been accepted a
     * moment earlier. What refuses it is the state the campaign is in, which is
     * frequently the state another tab, or a moderator, has just put it in — so the
     * client is told where the campaign actually is and what it can do from there
     * instead of having to reload and guess.
     */
    @ExceptionHandler(ProjectTransitionNotAllowedException.class)
    public ProblemDetail handleTransitionNotAllowed(ProjectTransitionNotAllowedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/project-transition-not-allowed"));
        problem.setTitle("Transition not allowed");
        problem.setDetail("A project in " + exception.from() + " cannot move to " + exception.to() + ".");
        problem.setProperty("code", "PROJECT_TRANSITION_NOT_ALLOWED");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("state", exception.from().name());
        meta.put("requested", exception.to().name());
        // Empty for a terminal state, which is what tells a client to stop offering
        // the action rather than to retry it.
        meta.put("allowed", names(exception.allowedInstead()));
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 409 for an approved campaign with nothing to be live with.
     *
     * <p>See {@link ProjectNotLaunchableException}: this becomes unreachable when
     * #37 refuses the submission, and until then it is what stands between a
     * moderator's approval and a constraint violation.
     */
    @ExceptionHandler(ProjectNotLaunchableException.class)
    public ProblemDetail handleNotLaunchable(ProjectNotLaunchableException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-launchable"));
        problem.setTitle("Project cannot launch");
        problem.setDetail("A campaign cannot go live until its funding goal and duration are set.");
        problem.setProperty("code", "PROJECT_NOT_LAUNCHABLE");
        problem.setProperty("meta", Map.of("missing", exception.missing()));
        return problem;
    }

    /**
     * 400 for one field of an edit.
     *
     * <p>The field name is in {@code meta} so that the editor can put the message
     * beside the input rather than in a banner above a long form — which is the
     * difference between a creator fixing it and a creator writing to support.
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
     * The value objects reject what they cannot represent — a cover image with no
     * extent, a currency that is not a currency, an amount with three decimal
     * places. Reaching here means input the binding accepted and the type did not,
     * which is still the client's problem.
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

    private static List<String> names(List<ProjectState> states) {
        return states.stream().map(ProjectState::name).toList();
    }
}
