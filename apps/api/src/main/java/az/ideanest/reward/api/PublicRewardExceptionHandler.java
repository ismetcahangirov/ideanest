package az.ideanest.reward.api;

import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link PublicRewardController}'s one refusal, as an RFC 9457 problem detail.
 *
 * <p>Its own advice rather than a type added to {@code RewardExceptionHandler}'s
 * {@code assignableTypes}, for the reason that handler already gives about sibling
 * issues in flight: that one line is the file two parallel branches would collide in.
 * It is also honest about the shape — the creator's endpoints have nine ways to fail
 * and this one has exactly one, because a public read validates nothing, locks
 * nothing, and writes nothing.
 */
@RestControllerAdvice(assignableTypes = PublicRewardController.class)
public class PublicRewardExceptionHandler {

    /**
     * 404 for a campaign that does not exist, and for one whose state is not public.
     *
     * <p>The same body and the same code the project and reward modules already use,
     * because the fact being reported is identical and a client that handles
     * {@code PROJECT_NOT_FOUND} should not need a second branch for it.
     *
     * <p><strong>Not a 403</strong>, and the distinction matters more here than
     * anywhere else in this module: this endpoint takes no credential, so a 403 would
     * be an oracle any stranger could ask. What it would report on is a campaign
     * somebody is still drafting, or one trust and safety has just suspended — which
     * are the two facts §6.1's hidden states exist to keep.
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
