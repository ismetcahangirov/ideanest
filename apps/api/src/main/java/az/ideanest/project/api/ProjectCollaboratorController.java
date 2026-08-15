package az.ideanest.project.api;

import az.ideanest.project.application.CollaboratorService;
import az.ideanest.shared.EmailAddress;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people on one campaign: who is on it, and inviting somebody else.
 *
 * <p>Split from {@link CollaboratorController} by what the path is about. These two
 * are addressed by the campaign, because "invite somebody" only means anything
 * relative to one; changing or withdrawing a grant is addressed by the grant, which
 * knows which campaign it belongs to.
 *
 * <p>Authorisation is not decided here. Both methods pass the caller's identifier
 * down, and {@code ProjectAccess} decides whether that identifier may manage this
 * campaign's team — the same one place that answers the question for every other
 * endpoint in the module.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/collaborators")
public class ProjectCollaboratorController {

    private final CollaboratorService collaborators;
    private final Clock clock;

    public ProjectCollaboratorController(CollaboratorService collaborators, Clock clock) {
        this.collaborators = collaborators;
        this.clock = clock;
    }

    /**
     * Everybody on the campaign, oldest invitation first.
     *
     * <p>Including the invitations nobody has accepted and the grants that were
     * withdrawn: a list that hid a revoked collaborator would make the revocation
     * look as though it had not happened, and "who used to have access" is the
     * question this list is asked after a leak.
     */
    @GetMapping
    public List<CollaboratorResponse> list(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        Instant now = clock.instant();
        return collaborators.list(projectId, callerOf(accessToken)).stream()
                .map(collaborator -> CollaboratorResponse.of(collaborator, now))
                .toList();
    }

    /**
     * Invites an address, with the capabilities it is being granted.
     *
     * <p>201 for the grant that now exists, and a {@code Location} header pointing at
     * the resource that changes it. <strong>No token in the response</strong>: it is
     * sent to the address, which is the only thing that makes an invitation an
     * invitation rather than a way for the inviter to add an account they control.
     */
    @PostMapping
    public ResponseEntity<CollaboratorResponse> invite(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody InviteCollaboratorRequest request) {

        CollaboratorResponse invited = CollaboratorResponse.of(
                collaborators.invite(
                        projectId,
                        callerOf(accessToken),
                        // Normalised here, so that the address stored, the address
                        // compared at acceptance, and the address in the unique
                        // index are one value. See EmailAddress.
                        EmailAddress.of(request.email()),
                        request.capabilities()),
                clock.instant());

        return ResponseEntity.created(URI.create("/v1/collaborators/" + invited.id())).body(invited);
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
