package az.ideanest.user.api;

import az.ideanest.user.application.PublicProfiles;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * P-07's switch: whether this account has a public profile page (#274).
 *
 * <p><strong>The only write in the whole of §4.2.</strong> Everything else on a profile —
 * the page, the campaigns the account created, P-04's backed archive — is a public read,
 * and this is the setting that decides whether any of them answer. Its own controller
 * rather than a method on {@link MeController} for the reason {@link PublicProfileController}
 * gives about that file: three different audiences read {@code users}, and the file layout
 * is what keeps a public projection, an owner's projection and a write from acquiring each
 * other's shape by sharing a class.
 *
 * <p><strong>{@code /v1/me/profile-visibility} rather than {@code PATCH /v1/me}.</strong>
 * There is no general account-edit endpoint on this service and this change does not
 * justify inventing one: a PATCH over the whole account is a surface every future field
 * joins by default, and the first one added without thinking becomes writable by anybody
 * holding a token. A path that names the one setting can only ever change that setting.
 *
 * <p><strong>PATCH rather than PUT.</strong> The request carries one field of an account
 * that has many, so PUT would promise that everything absent from the body is being
 * cleared — which is emphatically not what this does.
 *
 * <p><strong>Authenticated, by falling through to {@code SecurityConfiguration}'s
 * catch-all rather than being named in it.</strong> That is also what puts it out of reach
 * of an account inside §17.4's deletion grace period, which is right and worth stating,
 * because it looks at first like a control such an account should keep: the grace period
 * ends in anonymisation, {@code User.anonymise} sets {@code PRIVATE} itself, and the
 * setting is therefore already decided for them. What they would be able to do with it in
 * the meantime is turn the page back <em>on</em>.
 */
@RestController
public class ProfileVisibilityController {

    private final PublicProfiles profiles;

    public ProfileVisibilityController(PublicProfiles profiles) {
        this.profiles = profiles;
    }

    /**
     * Turns the caller's profile page on or off.
     *
     * <p>204 rather than the resulting state. The request names the state it wants and the
     * service refuses nothing that reached this far, so a body would be the request echoed
     * back — which is what the toggles in {@code BackerSignalController} deliberately do
     * <em>not</em> do either, and for the opposite half of the same reason: those are
     * idempotent writes whose outcome a client cannot infer from what it sent, and this is
     * one whose outcome is exactly what it sent.
     *
     * @param accessToken the caller. The account is taken from our own signature and never
     *     from the path or the body, as everywhere else on this service: an endpoint that
     *     took it from the request would let anybody withdraw somebody else's profile
     */
    @PatchMapping("/v1/me/profile-visibility")
    public ResponseEntity<Void> setVisibility(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody ProfileVisibilityRequest request) {

        UUID accountId = UUID.fromString(accessToken.getSubject());
        profiles.setVisibility(accountId, request.visibility());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
