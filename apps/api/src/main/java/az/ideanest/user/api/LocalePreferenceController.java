package az.ideanest.user.api;

import az.ideanest.user.application.UserAccounts;
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
 * P-10's language half: which of §21.1's four languages this account is written to (#324).
 *
 * <p><strong>The column has existed since V2 and nothing has ever written it.</strong>
 * {@code users.locale} is set once, at registration, from a field the sign-up form does not
 * send, and {@code User.setLocale} had no caller at all — so every account on the platform
 * holds the {@code 'az'} default and no person can change it. That is the gap this closes,
 * and it is a smaller one than P-10: the currency half stays absent for the reasons §4.2's
 * block quote gives, and the interface strings this setting selects between are §21.1's
 * message catalogue rather than anything served from here. What this endpoint decides is
 * the language of the mail {@code auth} and {@code notification} compose, which is read
 * from {@code UserAccount.locale} and is already honoured — those messages have been
 * addressed in a language the account never got to choose.
 *
 * <p><strong>{@code /v1/me/locale} rather than {@code PATCH /v1/me}.</strong> The same
 * argument {@link ProfileVisibilityController} makes and it does not weaken by being made
 * twice: there is no general account-edit endpoint on this service, and a PATCH over the
 * whole account is a surface that every future column joins by default. {@code users} holds
 * the email address, the slug, the deletion schedule and the suspension — three of which
 * have their own endpoint with their own rules precisely because writing them is not
 * "editing an account" — and a path that names one setting can only ever change that
 * setting.
 *
 * <p><strong>PATCH rather than PUT.</strong> The body carries one field of an account with
 * many, and PUT would promise that everything absent from it is being cleared. It is a
 * promise this could not keep even if it wanted to: the address and the slug are not
 * writable here at all.
 *
 * <p><strong>Authenticated, by falling through to {@code SecurityConfiguration}'s catch-all
 * rather than being named in it.</strong> Nothing about a language preference is public and
 * the account is the token's subject, so there is no matcher to add — and the consequence of
 * adding none is that this needs {@code ACCOUNT_ACTIVE} like everything else that is not
 * listed, so an account inside §17.4's deletion grace period cannot reach it. That is a
 * decision rather than an oversight, and the honest version of it is that both answers are
 * defensible: what such an account would change with this is the language of the notices the
 * grace period itself sends. The list it would have to join — {@code /v1/me},
 * {@code /v1/me/export}, {@code /v1/me/deletion} — is "listed rather than derived, so that
 * adding an endpoint never quietly adds a permission", and those three are look at yourself,
 * take your data, change your mind. A preference is none of the three; the language the
 * notices are written in is the one the account chose while it was still active, and a
 * person who wants to change it can cancel the deletion, which is one of the three.
 */
@RestController
public class LocalePreferenceController {

    private final UserAccounts users;

    public LocalePreferenceController(UserAccounts users) {
        this.users = users;
    }

    /**
     * Sets the language this account is written to.
     *
     * <p>204 rather than the resulting state. The request names the state it wants, and the
     * service refuses nothing that reached this far — the four languages are checked by
     * {@link LocaleRequest} before the handler runs, and there is no second rule underneath
     * that could decide otherwise — so a body would be the request echoed back to a client
     * that already knows what it sent.
     *
     * @param accessToken the caller. The account is taken from our own signature and never
     *     from the path or the body, as everywhere else on this service: an endpoint that
     *     took it from the request would let anybody set somebody else's language, which is
     *     a quiet way to make another person's mail unreadable
     * @param request the language, required
     */
    @PatchMapping("/v1/me/locale")
    public ResponseEntity<Void> setLocale(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody LocaleRequest request) {

        UUID accountId = UUID.fromString(accessToken.getSubject());
        users.setLocale(accountId, request.locale());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
