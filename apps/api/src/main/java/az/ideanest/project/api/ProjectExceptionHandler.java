package az.ideanest.project.api;

import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.NotAModeratorException;
import az.ideanest.project.application.ProjectFieldLockedException;
import az.ideanest.project.application.ProjectFieldRejectedException;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.ProjectNotLaunchableException;
import az.ideanest.project.application.ProjectNotSubmittableException;
import az.ideanest.project.application.ProjectTransitionNotAllowedException;
import az.ideanest.project.application.RemindersClosedException;
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
@RestControllerAdvice(
        assignableTypes = {
            ProjectController.class,
            ProjectModerationController.class,
            // The public pre-launch endpoints raise four of the five failures
            // below unchanged — a campaign that does not exist, a field that is not
            // an address, a value type that refused its input. Listing the
            // controller here rather than writing a second advice is the same
            // reasoning ProjectProblems gives: one refusal should not have two
            // bodies, and the second one to be edited is the one nobody notices.
            PrelaunchController.class,
            // And the public campaign page (#119), which raises exactly one of the
            // failures below: a campaign that does not exist, or one whose state is
            // not public. Both must come back as the same 404 with the same code,
            // which is the whole reason for listing it here rather than letting the
            // exception escape — an unhandled one on a permitAll endpoint reaches
            // Spring Security's error dispatch and comes back as 401, which tells an
            // anonymous visitor to sign in to see a campaign that does not exist.
            PublicProjectController.class,
            // And the creator's dashboard (#93), which raises exactly two of the
            // failures below and needs both answered as they already are: a 404 for
            // a campaign the caller is not party to, so that the endpoint does not
            // confirm which identifiers are real, and a 403 for a collaborator whose
            // grant does not include VIEW_FINANCES.
            DashboardController.class
        })
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
     * 409 for a campaign §5.3 will not let out of the door.
     *
     * <p>The refusal names every unmet requirement rather than the first, and gives
     * each one the editor section that fixes it — the same routing the checklist
     * endpoint gives, so a client refused here points at the controls it would have
     * pointed at anyway rather than showing a banner the creator has to interpret.
     *
     * <p>{@code detail} deliberately does not enumerate the requirements. It is
     * prose, it would be a sentence of unbounded length on a campaign missing eight
     * things, and §10.4 is explicit that a client branches on {@code code} and
     * {@code meta} rather than on prose.
     */
    @ExceptionHandler(ProjectNotSubmittableException.class)
    public ProblemDetail handleNotSubmittable(ProjectNotSubmittableException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-submittable"));
        problem.setTitle("Campaign is not ready to submit");
        problem.setDetail("Some of what a campaign needs before it can be reviewed is still missing.");
        problem.setProperty("code", "PROJECT_NOT_SUBMITTABLE");
        problem.setProperty("meta", Map.of("unmet", unmet(exception)));
        return problem;
    }

    private static List<Map<String, Object>> unmet(ProjectNotSubmittableException exception) {
        return exception.unmet().stream()
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("requirement", item.requirement().name());
                    entry.put("label", item.requirement().label());
                    entry.put("section", item.requirement().section().key());
                    entry.put("detail", item.detail());
                    return entry;
                })
                .toList();
    }

    /**
     * 409 for an approved campaign with nothing to be live with.
     *
     * <p>See {@link ProjectNotLaunchableException}. Unreachable through the API now
     * that a submission is checked against §5.3 — a campaign with no goal cannot
     * reach {@code APPROVED} to be launched from — and kept because it is the last
     * thing between a campaign that got there another way and a constraint
     * violation served as a 500. A guard whose whole value is that it never fires
     * is not a guard to delete the moment it stops firing.
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
     * 409 for a reminder asked of a campaign that is not collecting them.
     *
     * <p>Not a 404: the caller is looking at a page we served them, so there is
     * nothing left to hide. Not a 400 either — the request was well formed and
     * would have been accepted a moment earlier, and frequently was: the usual way
     * to reach this is to leave a pre-launch page open until the campaign launches.
     *
     * <p>The body carries the state because the client's next move depends on it:
     * a campaign that is {@code LIVE} should be offered to the visitor to back,
     * and a campaign that was cancelled should not be offered at all. A client that
     * could only see "409" would have to guess.
     */
    @ExceptionHandler(RemindersClosedException.class)
    public ProblemDetail handleRemindersClosed(RemindersClosedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/reminders-closed"));
        problem.setTitle("Not collecting reminders");
        problem.setDetail("This campaign has already opened, so there is nothing left to be reminded about.");
        problem.setProperty("code", "REMINDERS_CLOSED");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("state", exception.state().name());
        meta.put("acceptedIn", names(RemindersClosedException.ACCEPTED_IN));
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 409 for an edit to a field §5.3 froze at launch.
     *
     * <p>Not a 400, for the reason {@link ProjectFieldLockedException} gives and the
     * one {@link #handleTransitionNotAllowed} already gives: the value is fine and
     * the campaign's state is what refuses it. {@code meta} carries the field so the
     * editor can highlight it, and the state so it can say why without asking again
     * — and so that a client whose page is out of date learns, from the refusal, that
     * the campaign has gone live.
     */
    @ExceptionHandler(ProjectFieldLockedException.class)
    public ProblemDetail handleFieldLocked(ProjectFieldLockedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/project-field-locked"));
        problem.setTitle("Field locked");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "PROJECT_FIELD_LOCKED");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("field", exception.field());
        meta.put("state", exception.state().name());
        problem.setProperty("meta", meta);
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
