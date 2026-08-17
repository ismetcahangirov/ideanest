package az.ideanest.pledge.api;

import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link PublicBackerController}'s one refusal, as an RFC 9457 problem detail (§10.4).
 *
 * <p>Its own advice rather than a type added to {@code PledgeExceptionHandler}, which
 * is the argument {@code PublicRewardExceptionHandler} already makes about sibling
 * issues in flight: that file is the one two parallel branches collide in, and #56 is
 * editing it right now. It is also honest about the shape — the checkout has nine ways
 * to fail and a public read has exactly one, because it validates nothing, locks
 * nothing, and writes nothing.
 */
@RestControllerAdvice(assignableTypes = PublicBackerController.class)
public class PublicBackerExceptionHandler {

    /**
     * 404 for a campaign that does not exist, and for one whose state is not public.
     *
     * <p>The same body and the same code every other module uses for this fact, so a
     * client handling {@code PROJECT_NOT_FOUND} needs no second branch.
     *
     * <p><strong>Not a 403.</strong> This endpoint takes no credential, so a 403 would
     * be an oracle any stranger could ask, and what it would report on is a campaign
     * somebody is still drafting or one trust and safety has just suspended.
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
}
