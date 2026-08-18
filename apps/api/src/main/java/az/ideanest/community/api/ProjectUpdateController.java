package az.ideanest.community.api;

import az.ideanest.community.application.ProjectUpdateService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishing an update. §10.2's {@code POST /v1/projects/{id}/updates}, and §4.7's
 * CD-12.
 *
 * <p><strong>A separate controller from {@link PublicProjectUpdateController}, on the
 * same path.</strong> Spring routes on the method and the filter chain permits only the
 * {@code GET} — the same split {@code ProjectController} and {@code PrelaunchController}
 * already make for {@code /v1/projects/{id}/prelaunch}, and for the same reason: one
 * class here requires a bearer token and the other must not, and a reader should be able
 * to tell which is which without opening the security configuration.
 *
 * <p><strong>There is no edit and no delete.</strong> §10.2 gives an update those two
 * endpoints nowhere, and the omission is not an accident of the specification: an update
 * is a statement to people who have already read it. Withdrawing one is AD-09's content
 * moderation, which is not built. See the pull request for what that leaves a creator
 * unable to do.
 *
 * <p>Authorisation is not decided here. The caller's identifier goes down to
 * {@code ProjectUpdateService}, which asks {@code ProjectAccess} — the one place in the
 * service where "who may act on this campaign" is settled.
 */
@RestController
public class ProjectUpdateController {

    private final ProjectUpdateService updates;

    public ProjectUpdateController(ProjectUpdateService updates) {
        this.updates = updates;
    }

    /**
     * Publishes an update, now or at the moment it was scheduled for.
     *
     * <p><strong>201 with the update, and no {@code Location} header.</strong> A created
     * resource should say where it is, and this one has nowhere to point: §10.2 gives an
     * update no address of its own. Pointing at the list would be a header a client
     * cannot use for what {@code Location} means.
     *
     * <p><strong>No {@code Idempotency-Key}.</strong> §10.3 requires one on payment
     * mutations, and this is not one. A retried publish creates a second update, which
     * is visible, numbered, and something the creator can see they did — unlike a second
     * charge.
     */
    @PostMapping("/v1/projects/{projectId}/updates")
    public ResponseEntity<ProjectUpdateResponse> publish(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody PublishUpdateRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProjectUpdateResponse.of(
                        updates.publish(projectId, callerOf(accessToken), request.toCommand())));
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
