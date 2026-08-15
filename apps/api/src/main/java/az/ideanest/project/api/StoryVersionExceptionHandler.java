package az.ideanest.project.api;

import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.StoryVersionNotFoundException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link StoryVersionController}'s own refusals.
 *
 * <p>Scoped to that controller alone, which is why it is a separate class from
 * {@link StoryExceptionHandler}: both exceptions below can only be raised by these
 * three endpoints, and handling them across {@link ProjectController} as well would
 * put a second translation of {@code ProjectNotFoundException} in front of a
 * controller that already has one. Two translations of one refusal drift, and the
 * drift shows up as the same failure answering differently depending on which
 * endpoint produced it.
 */
@RestControllerAdvice(assignableTypes = StoryVersionController.class)
public class StoryVersionExceptionHandler {

    /**
     * 404 for a version number that is gone.
     *
     * <p>Not concealment, unlike the 404 below: the caller has already been
     * authorised for the campaign by the time this can be thrown. The reason is
     * almost always retention — fifty versions is a day and a half of editing — so
     * the detail says the version is no longer kept rather than that it never
     * existed, and a creator following a link they kept from last week learns which
     * of the two happened.
     */
    @ExceptionHandler(StoryVersionNotFoundException.class)
    public ProblemDetail handleVersionNotFound(StoryVersionNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/story-version-not-found"));
        problem.setTitle("No such story version");
        problem.setDetail("That version of the story is no longer kept. Only the most recent versions are.");
        problem.setProperty("code", "STORY_VERSION_NOT_FOUND");
        problem.setProperty("meta", Map.of("number", exception.number()));
        return problem;
    }

    /**
     * 404 for a campaign that does not exist, and for one this caller may not see.
     *
     * <p>Deliberately the same answer, exactly as in {@link ProjectExceptionHandler}
     * and for the same reason: a draft's story is an unreleased product, and
     * distinguishing "not yours" from "not there" would turn the version history
     * into an oracle for what other people are preparing.
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
}
