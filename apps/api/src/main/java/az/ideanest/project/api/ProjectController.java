package az.ideanest.project.api;

import az.ideanest.project.application.ProjectEditingService;
import az.ideanest.project.application.ProjectTransitionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A creator's campaign: creating it, editing it, and moving it through its life.
 *
 * <p>Six endpoints, one response type. The three that change state do nothing
 * themselves — they name a transition and hand it to
 * {@link ProjectTransitionService}, which is the only thing in the service that
 * writes {@code projects.state} and the only thing that records having done so.
 *
 * <p>Authorisation is not decided here either. Every method passes the caller's
 * identifier down, and {@code ProjectAccess} decides what that identifier is
 * allowed to reach — one place, so that #38 widening it to collaborators changes
 * one file rather than six methods.
 *
 * <p>The subject of the access token is where the caller comes from, never a path
 * or body parameter. A creator identifier a client could choose would let anybody
 * edit anybody's campaign.
 */
@RestController
@RequestMapping("/v1/projects")
public class ProjectController {

    private final ProjectEditingService editing;
    private final ProjectTransitionService transitions;
    private final ProjectEditResponses responses;

    public ProjectController(
            ProjectEditingService editing, ProjectTransitionService transitions, ProjectEditResponses responses) {
        this.editing = editing;
        this.transitions = transitions;
        this.responses = responses;
    }

    /**
     * Opens a draft.
     *
     * <p>201 with a {@code Location} header pointing at the editor projection: the
     * client's next request is that one, and a created resource should say where it
     * is.
     */
    @PostMapping
    public ResponseEntity<ProjectEdit> create(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody CreateProjectRequest request) {

        ProjectEdit created = responses.of(editing.create(callerOf(accessToken), request.title()));
        return ResponseEntity.created(URI.create("/v1/projects/" + created.id() + "/edit")).body(created);
    }

    /** The campaign as its creator edits it. Anybody else is told it does not exist. */
    @GetMapping("/{id}/edit")
    public ProjectEdit forEdit(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        return responses.of(editing.forEdit(id, callerOf(accessToken)));
    }

    /**
     * Applies a partial edit and returns the whole campaign.
     *
     * <p>The whole campaign rather than the fields that changed, because an edit can
     * change a field the client did not send — clearing the subcategory when the
     * category moves — and a client that merged a partial response would keep a
     * value the server has just discarded.
     */
    @PatchMapping("/{id}")
    public ProjectEdit edit(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @RequestBody ProjectPatchRequest request) {

        return responses.of(editing.edit(id, callerOf(accessToken), request.toPatch()));
    }

    /** Sends the campaign for review. */
    @PostMapping("/{id}/submit")
    public ProjectEdit submit(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        return responses.of(transitions.submit(id, callerOf(accessToken)));
    }

    /** Takes an approved campaign live, now. */
    @PostMapping("/{id}/launch")
    public ProjectEdit launch(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        return responses.of(transitions.launch(id, callerOf(accessToken)));
    }

    /**
     * Stops a live campaign. Terminal, and the reason is shown to backers.
     *
     * <p>{@code POST} rather than {@code DELETE}: the campaign is not removed. It
     * keeps its page, its history, and its pledges — which are cancelled rather than
     * charged — and what is created is the cancellation.
     */
    @PostMapping("/{id}/cancel")
    public ProjectEdit cancel(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody CancelProjectRequest request) {

        return responses.of(transitions.cancel(id, callerOf(accessToken), request.reason()));
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
