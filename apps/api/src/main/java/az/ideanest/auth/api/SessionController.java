package az.ideanest.auth.api;

import az.ideanest.auth.application.SessionDirectory;
import az.ideanest.auth.application.SessionDirectory.SessionSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own devices.
 *
 * <p>Authenticated with an access token like any other account endpoint, and
 * deliberately not with the refresh cookie: revoking a device is a thing a user
 * does from a page they are already signed in on, and it should not be reachable
 * by a request a browser makes on its own.
 */
@RestController
@RequestMapping("/v1/auth/sessions")
public class SessionController {

    private final SessionDirectory sessions;

    public SessionController(SessionDirectory sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public List<SessionSummary> list(@AuthenticationPrincipal Jwt accessToken) {
        return sessions.listFor(userIdOf(accessToken), currentSessionOf(accessToken));
    }

    /**
     * Revokes a device.
     *
     * <p>Including the one the request came from, which is simply a sign-out.
     * Refusing that would be a rule with no purpose that a user would have to
     * be told about.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        // Not found and not yours answer the same way. Telling them apart would
        // make this a way of asking whether an identifier is a real session.
        return sessions.revoke(userIdOf(accessToken), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static UUID userIdOf(Jwt accessToken) {
        // From a signature we made, not from anything the caller chose.
        return UUID.fromString(accessToken.getSubject());
    }

    private static UUID currentSessionOf(Jwt accessToken) {
        String sessionId = accessToken.getClaimAsString("sid");
        return sessionId == null ? null : UUID.fromString(sessionId);
    }
}
