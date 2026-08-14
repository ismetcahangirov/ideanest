package az.ideanest.user;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.domain.SessionRevocationReason;
import az.ideanest.auth.infrastructure.SessionRepository;
import az.ideanest.auth.infrastructure.UserCredentialRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.domain.User;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Closing an account, over HTTP.
 *
 * <p>The two tests that carry the design are
 * {@link #closingAnAccountNeedsThePasswordNotJustAToken()} and
 * {@link #aClosingAccountMaySignInAndDoAlmostNothing()}. The first is why the
 * endpoint takes a password at all; the second is the whole reason the deletion
 * is deferred rather than done.
 */
class AccountDeletionApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    /**
     * A path that is behind authentication and is not one of the three an
     * account may still use while it is closing. It resolves to nothing, which
     * is the point: an active account gets 404 from the dispatcher and a closing
     * one never reaches it.
     */
    private static final String SOME_OTHER_ENDPOINT = "/v1/projects";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private UserCredentialRepository credentials;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private EmailAddress registeredUser() {
        EmailAddress email = EmailAddress.of("deletion" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);
        return email;
    }

    private ResponseEntity<Map<String, Object>> signIn(EmailAddress email) {
        return rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private static String accessTokenOf(ResponseEntity<Map<String, Object>> response) {
        return (String) response.getBody().get("accessToken");
    }

    private static String refreshTokenOf(ResponseEntity<Map<String, Object>> response) {
        return (String) response.getBody().get("refreshToken");
    }

    private ResponseEntity<Map<String, Object>> requestDeletion(String accessToken, String password) {
        return rest.exchange(
                "/v1/me/deletion",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", password), bearer(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<String> cancelDeletion(String accessToken) {
        return rest.exchange(
                "/v1/me/deletion", HttpMethod.DELETE, new HttpEntity<>(bearer(accessToken)), String.class);
    }

    private ResponseEntity<Map<String, Object>> me(String accessToken) {
        return rest.exchange(
                "/v1/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private User reload(EmailAddress email) {
        return users.findByEmailAndDeletedAtIsNull(email).orElseThrow();
    }

    // ------------------------------------------------------------------
    // Requesting deletion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("closing an account needs the password, not just a token")
    void closingAnAccountNeedsThePasswordNotJustAToken() {
        EmailAddress email = registeredUser();
        String accessToken = accessTokenOf(signIn(email));

        ResponseEntity<Map<String, Object>> refused = requestDeletion(accessToken, "not-the-password");

        // 403, not 401: the token was accepted. What failed is the second check
        // that this particular action requires, and telling the client to sign
        // in again would be advice for a different problem.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // And nothing happened. An access token alone is a fifteen-minute bearer
        // credential; if it were enough here it would be a vandalism tool.
        assertThat(reload(email).isDeletionPending()).isFalse();
    }

    @Test
    @DisplayName("an unauthenticated caller cannot close anything")
    void deletionIsBehindAuthentication() {
        assertThat(rest.exchange(
                                "/v1/me/deletion",
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of("password", PASSWORD), jsonHeaders()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("requesting deletion schedules it thirty days out and revokes every session")
    void requestingDeletionRevokesEverySession() {
        EmailAddress email = registeredUser();
        ResponseEntity<Map<String, Object>> phone = signIn(email);
        ResponseEntity<Map<String, Object>> laptop = signIn(email);

        ResponseEntity<Map<String, Object>> accepted = requestDeletion(accessTokenOf(laptop), PASSWORD);

        // 202: we have accepted an instruction to be carried out later, and the
        // account is still here.
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Instant scheduledFor = Instant.parse((String) accepted.getBody().get("scheduledFor"));
        assertThat(Duration.between(Instant.now(), scheduledFor))
                .isBetween(Duration.ofDays(29), Duration.ofDays(30));

        // Both devices, not just the one that asked. Someone closing an account
        // is often closing it because they no longer trust something.
        assertThat(refreshWith(refreshTokenOf(phone)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshWith(refreshTokenOf(laptop)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        UUID userId = reload(email).getId();
        assertThat(sessions.findByUserIdOrderByCreatedAtDesc(userId))
                .hasSize(2)
                .allSatisfy(session -> {
                    assertThat(session.isRevoked()).isTrue();
                    // Recorded, like every other revocation. USER_REVOKED
                    // because that is what happened: the user did it.
                    assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.USER_REVOKED);
                });
    }

    @Test
    @DisplayName("asking twice keeps the first date rather than pushing it out")
    void repeatingTheRequestDoesNotExtendTheGracePeriod() {
        EmailAddress email = registeredUser();

        Instant first = Instant.parse((String)
                requestDeletion(accessTokenOf(signIn(email)), PASSWORD).getBody().get("scheduledFor"));
        Instant second = Instant.parse((String)
                requestDeletion(accessTokenOf(signIn(email)), PASSWORD).getBody().get("scheduledFor"));

        // A client that retries must not move the date it was told. Otherwise a
        // stuck client postpones the deletion indefinitely and nobody notices.
        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------
    // What a closing account may do
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a closing account may sign in, read itself, and export — and nothing else")
    void aClosingAccountMaySignInAndDoAlmostNothing() {
        EmailAddress email = registeredUser();
        requestDeletion(accessTokenOf(signIn(email)), PASSWORD);

        // Sign-in still works, and it has to: it is the only way back from a
        // deletion requested in error, or requested by somebody else.
        ResponseEntity<Map<String, Object>> signedIn = signIn(email);
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = accessTokenOf(signedIn);

        ResponseEntity<Map<String, Object>> account = me(accessToken);
        assertThat(account.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The client has to be able to say "this account is closing", or the
        // user assumes the deletion failed and writes to support.
        assertThat(account.getBody().get("deletionScheduledAt")).isNotNull();

        assertThat(rest.exchange(
                                "/v1/me/export", HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Everything else is refused. A closing account that could still pledge
        // would be making commitments it has announced it will not be here to
        // meet — and the resulting rows would outlive it by law, not by choice.
        assertThat(rest.exchange(
                                SOME_OTHER_ENDPOINT,
                                HttpMethod.GET,
                                new HttpEntity<>(bearer(accessToken)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an account that is not closing reaches the same path normally")
    void anActiveAccountIsNotRefused() {
        EmailAddress email = registeredUser();
        String accessToken = accessTokenOf(signIn(email));

        // 404 rather than 403: authorisation passed and there is simply nothing
        // mapped there yet. Without this the test above would pass even if the
        // rule refused everybody.
        assertThat(rest.exchange(
                                SOME_OTHER_ENDPOINT,
                                HttpMethod.GET,
                                new HttpEntity<>(bearer(accessToken)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // Changing one's mind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelling inside the grace period restores the account")
    void cancellingRestoresTheAccount() {
        EmailAddress email = registeredUser();
        requestDeletion(accessTokenOf(signIn(email)), PASSWORD);

        String accessToken = accessTokenOf(signIn(email));
        assertThat(cancelDeletion(accessToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(reload(email).isDeletionPending()).isFalse();
        assertThat(me(accessToken).getBody().get("deletionScheduledAt")).isNull();

        // The restriction lifts with the next token, which is the fifteen-minute
        // window the design already accepts everywhere else.
        String fresh = accessTokenOf(signIn(email));
        assertThat(rest.exchange(SOME_OTHER_ENDPOINT, HttpMethod.GET, new HttpEntity<>(bearer(fresh)), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("cancelling when nothing is pending is not an error")
    void cancellingWithoutAPendingDeletionSucceeds() {
        EmailAddress email = registeredUser();

        // The caller wanted an account that is not being deleted and has one.
        assertThat(cancelDeletion(accessTokenOf(signIn(email))).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the export contains the account and none of its credentials")
    void theExportCarriesTheAccountAndNoSecrets() {
        EmailAddress email = registeredUser();
        ResponseEntity<Map<String, Object>> signedIn = signIn(email);
        String accessToken = accessTokenOf(signedIn);
        String refreshToken = refreshTokenOf(signedIn);

        String storedHash = credentials
                .findById(reload(email).getId())
                .orElseThrow()
                .getPasswordHash();

        ResponseEntity<String> export = rest.exchange(
                "/v1/me/export", HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), String.class);

        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = export.getBody();

        assertThat(body).contains("\"format\":\"ideanest.account-export.v1\"");
        assertThat(body).contains(email.value());
        assertThat(body).contains("\"name\":\"Test Person\"");
        // The security history is the part a person actually needs: it is what
        // answers "was somebody else in my account".
        assertThat(body).contains("\"sessions\"");
        assertThat(body).contains("\"verifications\"");
        assertThat(body).contains("EMAIL_VERIFICATION");

        // Never a credential. An export is a copy of what we know about
        // somebody, not a copy of their keys — and a password hash is the one
        // field here that is worth cracking, because people reuse passwords.
        assertThat(body).doesNotContain(storedHash);
        assertThat(body).doesNotContain("$argon2");
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("tokenHash");
        assertThat(body).doesNotContain(refreshToken);

        // Nothing between us and the client keeps a copy of this.
        assertThat(export.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("the export is rate limited")
    void theExportIsRateLimited() {
        EmailAddress email = registeredUser();
        String accessToken = accessTokenOf(signIn(email));

        List<HttpStatus> statuses = List.of(
                exportStatus(accessToken),
                exportStatus(accessToken),
                exportStatus(accessToken),
                exportStatus(accessToken),
                exportStatus(accessToken),
                exportStatus(accessToken));

        // An endpoint that returns everything we hold about a person, on demand,
        // as often as asked, is an exfiltration endpoint with a friendly name.
        assertThat(statuses).endsWith(HttpStatus.TOO_MANY_REQUESTS);
    }

    private HttpStatus exportStatus(String accessToken) {
        return HttpStatus.valueOf(rest.exchange(
                        "/v1/me/export", HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), String.class)
                .getStatusCode()
                .value());
    }

    private ResponseEntity<String> refreshWith(String refreshToken) {
        return rest.exchange(
                "/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refreshToken), jsonHeaders()),
                String.class);
    }
}
