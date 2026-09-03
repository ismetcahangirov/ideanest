package az.ideanest.admin.api;

import az.ideanest.admin.application.UserAdministrationService;
import az.ideanest.pledge.api.BackerPledgeListResponse;
import az.ideanest.pledge.application.BackerCursor;
import az.ideanest.user.UserProperties;
import az.ideanest.user.application.AdministeredAccount;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-04 over HTTP (#104): search, inspect, ban, and let back in.
 *
 * <p>Four endpoints. §10.2 names two of them — {@code GET /v1/admin/users} and
 * {@code POST /v1/admin/users/{id}/ban} — and the other two are required by the issue's
 * own definition of done: an account cannot be <em>inspected</em> through a list that
 * pages, and a ban that cannot be undone makes the first mistake permanent.
 * {@code docs/architecture.md} §10.2 is amended in the same change rather than left
 * describing an API that is not the API.
 *
 * <p><strong>Every response is {@code no-store}.</strong> These carry other people's email
 * addresses, and a shared cache or a browser disk cache holding one is a disclosure that
 * survives signing out. It is the same rule the backer report and the address endpoints
 * follow, for the same reason.
 *
 * <p><strong>Staff only, and the check is in the service.</strong> Not here: an annotation
 * on a controller method is one somebody forgets on the fifth endpoint, and the service is
 * also where the audit row is written — the two belong together, because an authorised
 * action nobody recorded and a recorded action nobody authorised are the same bug from
 * opposite ends.
 */
@RestController
@RequestMapping("/v1/admin/users")
public class AdminUserController {

    private final UserAdministrationService accounts;
    private final UserProperties properties;

    public AdminUserController(UserAdministrationService accounts, UserProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    /**
     * The account list, filtered and paged.
     *
     * <p>{@code query} matches an address, a display name or a profile slug, because those
     * are the three things a complaint arrives holding. {@code suspended=true} is the
     * "who is stopped" list, which V40's partial index serves.
     */
    @GetMapping
    public ResponseEntity<AdminUserListResponse> search(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "suspended", defaultValue = "false") boolean suspendedOnly,
            @RequestParam(name = "after", required = false) UUID after,
            @RequestParam(name = "limit", required = false) Integer limit) {

        List<AdministeredAccount> found =
                accounts.search(callerOf(accessToken), query, suspendedOnly, after, limit);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AdminUserListResponse.of(found, effectiveLimit(limit)));
    }

    /** One account, with its verification status and its suspension. Audited like the list. */
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserResponse> inspect(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AdminUserResponse.of(accounts.inspect(callerOf(accessToken), id)));
    }

    /**
     * What one account has backed, newest first — #404.
     *
     * <p><strong>The context a suspension was being taken without.</strong> The account
     * directory's own copy tells a moderator that suspending somebody "changes nothing about
     * the campaigns they created or the pledges they made" — and offered no way to see
     * either. This is the pledges half. The campaigns half is
     * {@code GET /v1/admin/projects?creatorId=…}, which is the same change; the standing is
     * {@link #inspect} above, which already existed and had no screen.
     *
     * <p><strong>The body is {@link BackerPledgeListResponse}, the same one
     * {@code GET /v1/me/pledges} serves</strong>, and reusing it is the decision rather than
     * an economy. A moderator deciding about somebody's account should be looking at what
     * that person is looking at; a staff-shaped summary beside it would be a second
     * description of the same pledges, free to disagree with the first, and the disagreement
     * would surface inside a support conversation.
     *
     * <p>{@code no-store}, like everything else on this controller and more so: this carries
     * every amount one person has committed on the platform.
     *
     * @param cursor the previous page's {@code nextCursor}, or absent for the first. Opaque,
     *     and the same value the account's own list produces
     * @return 404 for an identifier that names nothing or a deleted account — not an empty
     *     page. "Has backed nothing" and "is not a person" are different answers, and a
     *     moderator acting on the first when the second is true is acting on a typo
     */
    @GetMapping("/{id}/pledges")
    public ResponseEntity<BackerPledgeListResponse> pledges(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BackerPledgeListResponse.of(
                        accounts.pledgesOf(callerOf(accessToken), id, BackerCursor.decode(cursor), limit)));
    }

    /**
     * AD-04's ban: the account is stopped and every session it holds is revoked.
     *
     * <p>{@code POST} to an action rather than {@code DELETE} on the account: nothing is
     * removed, the person's campaigns and pledges stay exactly where they are, and what is
     * created is the suspension. Idempotent by construction — a second ban keeps the first
     * decision, so the reason and the author a support conversation is about do not change
     * under it.
     */
    @PostMapping("/{id}/ban")
    public ResponseEntity<AdminUserResponse> ban(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @Valid @RequestBody SuspendAccountRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AdminUserResponse.of(accounts.suspend(callerOf(accessToken), id, request.reason())));
    }

    /**
     * The way back.
     *
     * <p>Not in §10.2, and the reason it exists is the difference between an account and a
     * campaign: a campaign's suspension is terminal because its funding window has moved
     * on, and an account has no window. A ban with no reversal makes the first mistaken one
     * permanent, which is not a policy anybody chose.
     *
     * <p>Sessions are not restored, and could not be — the person signs in again.
     */
    @PostMapping("/{id}/reinstate")
    public ResponseEntity<AdminUserResponse> reinstate(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AdminUserResponse.of(accounts.reinstate(callerOf(accessToken), id)));
    }

    /**
     * The page size this request will actually get, for the cursor.
     *
     * <p>The same rule {@code UserDirectory} applies, and asked here because the response
     * needs to know whether a full page was returned — a client that asked for two hundred
     * and got a hundred has reached the ceiling, not the end of the list.
     */
    private int effectiveLimit(Integer limit) {
        UserProperties.Administration administration = properties.administration();
        if (limit == null || limit < 1) {
            return administration.defaultPageSize();
        }
        return Math.min(limit, administration.maxPageSize());
    }

    /** The account making the request, from our own signature and never from the body. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
