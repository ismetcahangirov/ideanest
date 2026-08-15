package az.ideanest.project.api;

import az.ideanest.project.application.CollaboratorService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One grant: changing it, withdrawing it, and accepting the invitation behind it.
 *
 * <p>Addressed by the grant rather than by the campaign, because the row already
 * says which campaign it belongs to and a client holding a grant identifier should
 * not have to also remember where it came from. {@code ProjectAccess} loads both and
 * decides whether the caller may manage that campaign's team.
 */
@RestController
@RequestMapping("/v1/collaborators")
public class CollaboratorController {

    private final CollaboratorService collaborators;
    private final Clock clock;

    public CollaboratorController(CollaboratorService collaborators, Clock clock) {
        this.collaborators = collaborators;
        this.clock = clock;
    }

    /**
     * Replaces a collaborator's capabilities with the ones sent.
     *
     * <p>The whole set, for the reason {@link CollaboratorCapabilitiesRequest} gives:
     * a merge would leave no way to take a capability away.
     */
    @PatchMapping("/{id}")
    public CollaboratorResponse changeCapabilities(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody CollaboratorCapabilitiesRequest request) {

        return CollaboratorResponse.of(
                collaborators.changeCapabilities(id, callerOf(accessToken), request.capabilities()),
                clock.instant());
    }

    /**
     * Withdraws a grant, or an invitation nobody has accepted.
     *
     * <p>{@code DELETE} and 204, because from the client's side the collaborator is
     * gone. The row is not: it is stamped as revoked, which is what keeps "who had
     * access to this draft, and until when" answerable. Withdrawing something already
     * withdrawn answers 204 as well — the client asked for a state the row is in.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        collaborators.revoke(id, callerOf(accessToken));
        return ResponseEntity.noContent().build();
    }

    /**
     * Claims an invitation.
     *
     * <p>Authenticated, and the account has to be the address the invitation was sent
     * to. The token in the path is the proof that the person holds the mailbox; the
     * access token is the proof of who they are here, and an invitation needs both —
     * the first alone would let a forwarded message put a stranger on the campaign.
     *
     * <p>{@code POST} rather than {@code GET}, even though a link in an email is a
     * {@code GET}: accepting creates a grant, and a mail client that prefetches links
     * would otherwise accept invitations nobody had read. The web client turns the
     * link into this call.
     */
    @PostMapping("/invitations/{token}/accept")
    public CollaboratorResponse accept(@AuthenticationPrincipal Jwt accessToken, @PathVariable String token) {
        return CollaboratorResponse.of(collaborators.accept(token, callerOf(accessToken)), clock.instant());
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
