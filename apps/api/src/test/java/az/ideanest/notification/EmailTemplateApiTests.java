package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.MailServerStub;
import az.ideanest.user.infrastructure.UserRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * AD-15's two endpoints: who may use them, and what they answer.
 *
 * <p>The one worth reading is {@link #aTestSendTakesNoRecipient()}. Everything else here
 * is ordinary endpoint coverage; that one is about a decision — the endpoint deliberately
 * has no recipient parameter, because an authenticated route that takes an arbitrary
 * address and a platform-branded template is a way to send convincing payment mail to
 * anybody, at the cost of one compromised staff account. A test that only checked "staff
 * can send themselves a message" would pass just as happily against the dangerous
 * version, so this one checks where the message went.
 */
class EmailTemplateApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    /** The single address {@code application-test.yml} lists as a moderator. */
    private static final String MODERATOR_EMAIL = "moderator@ideanest.test";

    /** Shared across the class, and minted rather than signed in for. */
    private static Account MODERATOR;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenIssuer tokens;

    // ------------------------------------------------------------------
    // Who may
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the template list refuses an account that is not staff")
    void theListRefusesANonStaffAccount() {
        ResponseEntity<Map<String, Object>> response = get("/v1/admin/email-templates", account().accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "NOT_A_MODERATOR");
    }

    @Test
    @DisplayName("and an unauthenticated caller")
    void theListRefusesAStranger() {
        assertThat(get("/v1/admin/email-templates", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /**
     * That the list is derived from the enum rather than maintained beside it.
     *
     * <p>Asserted as "exactly the types with an email column", so that a type gaining or
     * losing email changes this list by changing {@code NotificationType} — and never by
     * somebody remembering to change a second place.
     */
    @Test
    @DisplayName("the list is exactly the types §4.10 gives an email column")
    void theListIsEveryEmailedType() {
        ResponseEntity<Map<String, Object>> response =
                get("/v1/admin/email-templates", moderator().accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> listed = typesIn(response);

        assertThat(listed)
                .containsExactlyElementsOf(List.of(NotificationType.values()).stream()
                        .filter(type -> type.channels().contains(NotificationChannel.EMAIL))
                        .map(Enum::name)
                        .toList());
        assertThat(listed)
                .as("the one type with copy and no email column is not offered")
                .doesNotContain(NotificationType.DEADLINE_24H.name());
    }

    // ------------------------------------------------------------------
    // The preview
    // ------------------------------------------------------------------

    /**
     * That a preview is the message rather than JSON describing one.
     *
     * <p>The point of the endpoint is that somebody can look at it. A JSON envelope with
     * the markup in a string would need something to unwrap it before anybody could, and
     * that something would be a second renderer with its own bugs.
     */
    @Test
    @DisplayName("a preview is answered as the email body, with the subject in a header")
    void aPreviewIsTheMessage() {
        ResponseEntity<String> html = getRaw(
                "/v1/admin/email-templates/PLEDGE_CONFIRMED/preview", moderator().accessToken());

        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        // Named, because the sample document carries a title -- which is what the platform
        // sends since #249, and previewing the fallback wording would show a reviewer the
        // sentences it has stopped sending.
        assertThat(html.getHeaders().getFirst("X-Email-Subject"))
                .isEqualTo("Your pledge to Xari Bulbul Ceramics is confirmed");
        assertThat(html.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(html.getBody()).contains("<table").contains("120.00 AZN");
    }

    /**
     * The part nothing else renders, and therefore the part worth being able to look at.
     *
     * <p>A broken HTML layout is visible the moment somebody opens a preview. A broken
     * plain-text part is visible to nobody until a reader whose client prefers text
     * receives one.
     */
    @Test
    @DisplayName("the plain-text part can be previewed too")
    void theTextPartCanBePreviewed() {
        ResponseEntity<String> text = getRaw(
                "/v1/admin/email-templates/PLEDGE_CONFIRMED/preview?format=text",
                moderator().accessToken());

        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
        assertThat(text.getBody()).contains("120.00 AZN").doesNotContain("<table");
    }

    @Test
    @DisplayName("a type with no email column is refused rather than rendered")
    void aTypeWithNoEmailIsRefused() {
        ResponseEntity<Map<String, Object>> response = get(
                "/v1/admin/email-templates/DEADLINE_24H/preview", moderator().accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "TEMPLATE_NOT_EMAILED");
    }

    // ------------------------------------------------------------------
    // The test send
    // ------------------------------------------------------------------

    /**
     * The decision, checked where it can be checked: at the recipient.
     *
     * <p>See the class comment. The endpoint takes no address, so the only assertion that
     * distinguishes this design from the dangerous one is that the message arrived at the
     * caller's own.
     */
    @Test
    @DisplayName("a test send takes no recipient and goes to the caller's own address")
    void aTestSendTakesNoRecipient() throws Exception {
        MailServerStub.clear();

        ResponseEntity<Map<String, Object>> response = post(
                "/v1/admin/email-templates/PAYMENT_FAILED/test-send", moderator().accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        MimeMessage received = MailServerStub.awaitOne();
        assertThat(received.getAllRecipients()[0].toString())
                .as("the address on the calling account, and nowhere else")
                .contains(MODERATOR_EMAIL);
        assertThat(received.getSubject())
                .as("and it is the real message for that type, not a placeholder")
                .isEqualTo("Your payment for Xari Bulbul Ceramics did not go through");
    }

    @Test
    @DisplayName("a test send refuses an account that is not staff")
    void aTestSendRefusesANonStaffAccount() {
        ResponseEntity<Map<String, Object>> response = post(
                "/v1/admin/email-templates/PLEDGE_CONFIRMED/test-send", account().accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(String accessToken, UUID id) {
    }

    private Account account() {
        EmailAddress email = EmailAddress.of("templates-" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);
        return new Account(tokenFor(email), users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId());
    }

    /**
     * The one account this suite's configuration treats as platform staff.
     *
     * <p>Its token is minted rather than signed in for, exactly as
     * {@code ContentReportApiTests} does and for the reason that file spells out at
     * length: {@code application-test.yml} names one moderator address, six suites now
     * share it, and {@code sign-ins-per-email} is deliberately left at its real value of
     * five — so a seventh suite signing in would exhaust the window and fail somebody
     * else's tests with a 401 that has nothing to do with them.
     */
    private Account moderator() {
        if (MODERATOR != null) {
            return MODERATOR;
        }
        EmailAddress email = EmailAddress.of(MODERATOR_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Moderator"),
                    String.class);
        }
        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        MODERATOR = new Account(tokenFor(email), id);
        return MODERATOR;
    }

    private String tokenFor(EmailAddress email) {
        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return tokens.issue(
                        id,
                        UUID.randomUUID(),
                        new AccessTokenIssuer.AccountStanding(true, false),
                        false,
                        Instant.now())
                .value();
    }

    @SuppressWarnings("unchecked")
    private static List<String> typesIn(ResponseEntity<Map<String, Object>> response) {
        List<Map<String, Object>> templates = (List<Map<String, Object>>) response.getBody().get("templates");
        return templates.stream().map(template -> (String) template.get("type")).toList();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token) {
        return rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<String> getRaw(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}
