package az.ideanest.project.api;

import az.ideanest.project.application.StoryVersionService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The story's version history — contract §5.
 *
 * <p>Three endpoints, and <strong>no endpoint that writes a version</strong>. The
 * story is saved through {@code PATCH /v1/projects/{id}} with everything else, and
 * a version is a consequence of that save rather than a separate action a client
 * takes; see {@link StoryVersionService} for the rule that decides when one is
 * written. An explicit "save a version" call would be a second way to write a
 * story and the two would eventually disagree.
 *
 * <p>Authorisation is not decided here. Every method passes the caller's
 * identifier down to {@code ProjectAccess}, which is the one place that question is
 * answered — so #38 widening it to collaborators with {@code EDIT_STORY} changes
 * one file rather than three methods.
 */
@RestController
@RequestMapping("/v1/projects/{id}/story/versions")
public class StoryVersionController {

    private final StoryVersionService versions;
    private final StoryVersionResponses responses;
    private final ProjectEditResponses projects;

    public StoryVersionController(
            StoryVersionService versions, StoryVersionResponses responses, ProjectEditResponses projects) {
        this.versions = versions;
        this.responses = responses;
        this.projects = projects;
    }

    /**
     * Every kept version, newest first, without their documents.
     *
     * <p>Unpaged, because retention caps the list at fifty rows
     * ({@code ideanest.project.story.versions-kept}). Paging a response with a
     * known small ceiling would be an interface a client has to implement for a
     * second page that cannot exist.
     */
    @GetMapping
    public List<StoryVersionSummary> list(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        return responses.summaries(versions.list(id, callerOf(accessToken)));
    }

    /** One version and its document, so it can be read before anything is replaced. */
    @GetMapping("/{number}")
    public StoryVersionDetail get(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id, @PathVariable int number) {

        return responses.detailOf(versions.get(id, callerOf(accessToken), number));
    }

    /**
     * Makes an older version the current story.
     *
     * <p>Answers the whole {@code ProjectEdit}, like every other mutation in this
     * module: the editor's next action is to render the story it now holds, and a
     * response that only confirmed the restore would send it back for the document
     * it was already entitled to.
     *
     * <p>{@code POST} on a sub-resource rather than {@code PUT} on the story: this
     * creates a change rather than putting a representation, and it is not
     * idempotent in the way that matters — restoring twice can write a version the
     * first restore did not.
     */
    @PostMapping("/{number}/restore")
    public ProjectEdit restore(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id, @PathVariable int number) {

        return projects.of(versions.restore(id, callerOf(accessToken), number));
    }

    /**
     * The account making the request, as our own signature establishes it.
     *
     * <p>Not read from anything the caller could choose.
     */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
