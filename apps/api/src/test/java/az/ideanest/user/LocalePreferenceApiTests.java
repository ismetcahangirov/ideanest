package az.ideanest.user;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * §4.2's P-10, language half — {@code PATCH /v1/me/locale} and the read beside it (#324).
 *
 * <p><strong>What this suite is really about is that {@code users.locale} was writable by
 * nobody.</strong> The column has been there since V2 with a check constraint and a
 * default, {@code User.setLocale} had no caller, and the only value it ever took came from
 * a registration field the sign-up form does not send — so every account holds {@code 'az'}
 * and the mail this platform sends is addressed in a language its owner never chose. Half
 * of what is asserted here is therefore ordinary: a language goes in, the same language
 * comes out of {@code GET /v1/me}.
 *
 * <p>The other half is the part a narrow write gets wrong, and each has its own test:
 *
 * <ul>
 *   <li><strong>Absent is a refusal, not a default.</strong>
 *       {@link #aBodyWithNoLanguageIsRefused()} is the one property that separates this
 *       request from registration's, where an unstated locale means Azerbaijani. Here a
 *       client whose serialiser dropped the field would be answered 204 — a language change
 *       reported to somebody who would discover it had not happened by reading their next
 *       email in the wrong language.</li>
 *   <li><strong>The vocabulary is §21.1's four and the endpoint says so.</strong>
 *       {@link #anUnsupportedLanguageIsRefused()} sends a language this platform does not
 *       have. The value would be refused one layer down by
 *       {@code users_locale_supported} either way; the difference is 400 naming the field
 *       against a constraint violation surfacing as a 500.</li>
 *   <li><strong>Nobody sets anybody else's language.</strong>
 *       {@link #theLanguageSwitchNeedsAToken()} asserts the endpoint is still reached by
 *       {@code SecurityConfiguration}'s catch-all, which is the whole of its authorisation:
 *       it is named nowhere in that file, and an endpoint that acquired a
 *       {@code permitAll} by accident would take the account from a token it never
 *       checked.</li>
 * </ul>
 */
class LocalePreferenceApiTests extends AbstractIntegrationTest {

    /**
     * What this class's fixture accounts are called.
     *
     * <p><strong>Namespaced so they cannot be another suite's</strong>, for the reason
     * {@code ProfileEditorApiTests} and {@code PublicProfileApiTests} both state in full:
     * nothing deletes {@code users} between classes, several suites share a
     * {@code role + "-" + counter} convention with counters that all start at one, and the
     * suite that takes a handle first leaves the next one unable to register a password
     * against it — whereupon its sign-in answers 401, its next call carries
     * {@code Authorization: Bearer null}, and the failure surfaces three frames from the
     * cause.
     */
    private static final String HANDLE_PREFIX = "locale-preference-";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    // -----------------------------------------------------------------------
    // The read
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the owner's own read carries the language the account is set to")
    void theOwnersReadCarriesTheLanguage() {
        // Registered in Russian rather than left at the default, so that what this asserts
        // is the stored value and not a constant that happens to match it.
        Account owner = registered("reader", "ru");

        Map<String, Object> me = parse(me(owner));
        assertThat(me.get("locale")).isEqualTo("ru");
        // The client cannot derive this from anything else it holds: it is what the
        // preference screen opens on and what its Accept-Language has to say afterwards.
        assertThat(me.get("slug")).isEqualTo(owner.slug());
    }

    // -----------------------------------------------------------------------
    // The write
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a supported language is stored, and the owner's next read says so")
    void theLanguageChangesAndTheReadFollows() {
        Account owner = registered("switcher", "az");
        assertThat(parse(me(owner)).get("locale")).isEqualTo("az");

        ResponseEntity<String> response = setLocale(owner, "{\"locale\": \"tr\"}");

        // 204: the request named the state it wanted and nothing refused it, so a body
        // would be the request echoed back.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();

        // The read is the contract, and the column is the thing the mail templates read.
        // Both, because a write that only satisfied the projection would still send
        // somebody's password reset in the language they left behind.
        assertThat(parse(me(owner)).get("locale")).isEqualTo("tr");
        assertThat(jdbc().queryForObject("SELECT locale FROM users WHERE id = ?", String.class, owner.id()))
                .isEqualTo("tr");
    }

    @Test
    @DisplayName("setting the same language twice is not an error")
    void settingTheSameLanguageTwiceSucceeds() {
        Account owner = registered("idempotent", "az");

        assertThat(setLocale(owner, "{\"locale\": \"en\"}").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // A retry after a dropped connection, or a second tab submitting the same form.
        assertThat(setLocale(owner, "{\"locale\": \"en\"}").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc().queryForObject("SELECT locale FROM users WHERE id = ?", String.class, owner.id()))
                .isEqualTo("en");
    }

    // -----------------------------------------------------------------------
    // What it refuses
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a language this platform does not have is refused, and the account is left alone")
    void anUnsupportedLanguageIsRefused() {
        Account owner = registered("unsupported", "az");

        ResponseEntity<String> response = setLocale(owner, "{\"locale\": \"de\"}");

        assertRefuses(response, "That language is not supported");
        assertThat(jdbc().queryForObject("SELECT locale FROM users WHERE id = ?", String.class, owner.id()))
                .isEqualTo("az");
    }

    @Test
    @DisplayName("a body with no language in it is refused rather than silently succeeding")
    void aBodyWithNoLanguageIsRefused() {
        Account owner = registered("absent", "az");

        // Absent. Written as a JSON string rather than a map because the distinction being
        // asserted is between a key that is there and one that is not.
        assertRefuses(setLocale(owner, "{}"), "A language is required");
        // And blank, which is what a form with an untouched select sends. Only the field is
        // asserted here and not the message: blank fails @NotBlank and @Pattern both, and
        // which of the two messages the handler reports is not an order this suite should
        // freeze.
        ResponseEntity<String> blank = setLocale(owner, "{\"locale\": \"   \"}");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(blank)).containsOnlyKeys("locale");

        assertThat(jdbc().queryForObject("SELECT locale FROM users WHERE id = ?", String.class, owner.id()))
                .isEqualTo("az");
    }

    @Test
    @DisplayName("the language switch needs a token")
    void theLanguageSwitchNeedsAToken() {
        ResponseEntity<String> anonymous = rest.exchange(
                "/v1/me/locale",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"locale\": \"en\"}", jsonHeaders()),
                String.class);

        // The endpoint is not named in the security configuration at all and falls through
        // to the catch-all, which is what this asserts is still true. There is no account
        // in the request to take, so an unauthenticated call has nothing to change.
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** A registered, signed-in account: its token, its identifier and its slug. */
    private record Account(String accessToken, UUID id, String slug) {
    }

    private Account registered(String role, String locale) {
        String marker = HANDLE_PREFIX + role + "-" + SEQUENCE.incrementAndGet();
        String email = marker + "@example.com";

        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "Test " + role, "locale", locale),
                String.class);

        Map<String, Object> signedIn = parse(rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                String.class));

        Map<String, Object> account = jdbc().queryForMap("SELECT id, slug FROM users WHERE email = ?::citext", email);
        return new Account(
                (String) signedIn.get("accessToken"), (UUID) account.get("id"), (String) account.get("slug"));
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private ResponseEntity<String> me(Account owner) {
        return rest.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer(owner.accessToken())), String.class);
    }

    private ResponseEntity<String> setLocale(Account owner, String body) {
        return rest.exchange(
                "/v1/me/locale", HttpMethod.PATCH, new HttpEntity<>(body, bearer(owner.accessToken())), String.class);
    }

    /** 400, RFC 9457, and the field named so that a preference screen can say which. */
    private void assertRefuses(ResponseEntity<String> response, String message) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(response)).isEqualTo(Map.of("locale", message));
    }

    /** {@code ApiExceptionHandler}'s field-to-message map, off a 400. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> errors(ResponseEntity<String> response) {
        return (Map<String, Object>) parse(response).get("errors");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(ResponseEntity<String> response) {
        try {
            return json.readValue(response.getBody(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Not a JSON object: " + response.getBody(), e);
        }
    }
}
