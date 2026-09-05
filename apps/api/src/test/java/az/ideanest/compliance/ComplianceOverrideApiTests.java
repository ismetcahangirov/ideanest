package az.ideanest.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V66's overrides and the prohibition that makes them worth having — issue #436.
 *
 * <p><strong>The self-grant test is the one that matters, and it is asserted against the
 * database.</strong> #436 asks for the prohibition to be "enforced by a constraint, not by a
 * service check, with a test that tries it" — because the service is not the only thing that
 * ever writes to a table, and an incident is exactly when somebody would write an INSERT by
 * hand around a rule that is in the way.
 *
 * <p>The rest is the lifecycle an auditor reads: an override that expires because time passed
 * rather than because a job ran, one that was withdrawn early, and the fact that neither is
 * ever deleted.
 */
class ComplianceOverrideApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";
    private static final String ADMIN_EMAIL = "moderator@ideanest.test";

    private Account admin;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccessTokenIssuer tokens;

    @AfterEach
    void clear() {
        // Overrides only. `users` is left alone deliberately: other suites share
        // moderator@ideanest.test, and a teardown that removed accounts would take their
        // fixtures with it.
        new JdbcTemplate(dataSource).update("DELETE FROM compliance_overrides");
    }

    @Test
    @DisplayName("an override is granted, is live, and is drawn on the account it was applied to")
    void grantingAnOverride() {
        Account creator = account();
        Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);

        Map<String, Object> granted = grant(creator.id(), "IDENTITY_VERIFICATION", "DOCUMENT_UNAVAILABLE_ABROAD",
                "Passport is with the embassy in Ankara until October.", expires);

        assertThat(granted.get("requirement")).isEqualTo("IDENTITY_VERIFICATION");
        assertThat(granted.get("live")).isEqualTo(true);
        assertThat(granted.get("grantedBy")).isEqualTo(admin().id().toString());
        assertThat(granted.get("revokedAt")).isNull();

        Map<String, Object> history = history(creator.id());
        assertThat(history.get("liveCount")).isEqualTo(1);
        assertThat((List<?>) history.get("overrides")).hasSize(1);
    }

    @Test
    @DisplayName("nobody overrides a rule for their own account, and the database is what refuses")
    void nobodyOverridesForThemselves() {
        UUID self = admin().id();

        // The service refuses first, so the operator gets a sentence rather than an integrity
        // violation. 403 rather than 400: the request is well-formed and a different account
        // could make it, which is exactly the distinction between the two codes.
        ResponseEntity<Map<String, Object>> refused = rest.exchange(
                "/v1/admin/accounts/%s/compliance-overrides".formatted(self),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "requirement", "PAYOUT_DESTINATION",
                                "reason", "PLATFORM_ERROR",
                                "note", "Convenient.",
                                "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()),
                        authorised(admin().accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("SELF_GRANTED_OVERRIDE");

        // And the guarantee, which is the point of the test. The row is refused by
        // `compliance_overrides_grantor_is_not_the_subject` with the service out of the way --
        // so the rule holds against a hand-written INSERT during an incident, which is when
        // somebody would write one.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO compliance_overrides
                            (id, subject_user_id, requirement, reason, note, granted_by, expires_at)
                        VALUES (?, ?, 'PAYOUT_DESTINATION', 'PLATFORM_ERROR', 'By hand.', ?, now() + interval '1 day')
                        """,
                        UUID.randomUUID(),
                        self,
                        self))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("compliance_overrides_grantor_is_not_the_subject");
    }

    @Test
    @DisplayName("an override expires because time passed, not because a job ran")
    void anOverrideExpiresOnItsOwn() {
        Account creator = account();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Written straight in with a window that has already closed. There is no service call
        // that would produce this -- the ceiling and the past are both refused -- and that is
        // the point: what is being checked is that a stale row stops answering without
        // anything having swept it.
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO compliance_overrides
                    (id, subject_user_id, requirement, reason, note, granted_by, granted_at, expires_at)
                VALUES (?, ?, 'LEGAL_SUBJECT', 'PROVIDER_OUTAGE', 'During the March outage.', ?,
                        now() - interval '10 days', now() - interval '3 days')
                """,
                id,
                creator.id(),
                admin().id());

        Map<String, Object> history = history(creator.id());

        // Still there -- V66 never deletes -- and no longer doing anything.
        assertThat((List<?>) history.get("overrides")).hasSize(1);
        assertThat(history.get("liveCount")).isEqualTo(0);
        assertThat(((Map<?, ?>) ((List<?>) history.get("overrides")).get(0)).get("live"))
                .isEqualTo(false);
    }

    @Test
    @DisplayName("revoking withdraws it and keeps the row, because a withdrawal is a fact")
    void revokingKeepsTheRow() {
        Account creator = account();
        Map<String, Object> granted = grant(creator.id(), "AGREEMENT_SIGNATURE", "LEGAL_ADVICE",
                "Counsel confirmed a signature is not required for this subject.",
                Instant.now().plus(60, ChronoUnit.DAYS));
        String id = (String) granted.get("id");

        Map<String, Object> revoked = revoke(creator.id(), id);

        assertThat(revoked.get("live")).isEqualTo(false);
        assertThat(revoked.get("revokedAt")).isNotNull();
        assertThat(revoked.get("revokedBy")).isEqualTo(admin().id().toString());

        // "Granted until Friday and withdrawn on Wednesday" and "granted until Wednesday" are
        // the same window and different events, and only the first has somebody's name on the
        // withdrawal. Deleting the row would lose that.
        Map<String, Object> history = history(creator.id());
        assertThat((List<?>) history.get("overrides")).hasSize(1);
        assertThat(history.get("liveCount")).isEqualTo(0);

        // Idempotent: a second withdrawal keeps the first, because the first is the one that
        // stopped it working.
        Map<String, Object> again = revoke(creator.id(), id);
        assertThat(again.get("revokedAt")).isEqualTo(revoked.get("revokedAt"));
    }

    @Test
    @DisplayName("a window longer than the ceiling is refused, and the refusal says what would have been")
    void theWindowHasACeiling() {
        Account creator = account();

        ResponseEntity<Map<String, Object>> refused = rest.exchange(
                "/v1/admin/accounts/%s/compliance-overrides".formatted(creator.id()),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "requirement", "IDENTITY_VERIFICATION",
                                "reason", "VERIFIED_BY_OTHER_MEANS",
                                "note", "Indefinitely, please.",
                                "expiresAt",
                                        Instant.now().plus(400, ChronoUnit.DAYS).toString()),
                        authorised(admin().accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("code")).isEqualTo("INVALID_OVERRIDE_WINDOW");
        // An error that says "no" without saying "up to ninety days" is one the operator
        // answers by guessing.
        assertThat(((Map<?, ?>) refused.getBody().get("meta")).get("longestSeconds"))
                .isNotNull();
    }

    @Test
    @DisplayName("an account that is not staff is refused, and the refusal names the capability")
    void grantingNeedsTheCapability() {
        Account creator = account();
        Account stranger = account();

        ResponseEntity<Map<String, Object>> refused = rest.exchange(
                "/v1/admin/accounts/%s/compliance-overrides".formatted(creator.id()),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "requirement", "IDENTITY_VERIFICATION",
                                "reason", "PLATFORM_ERROR",
                                "note", "Trying it on.",
                                "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()),
                        authorised(stranger.accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("granting writes an audit row against the account it was applied to")
    void grantingIsAudited() {
        Account creator = account();
        grant(creator.id(), "PAYOUT_DESTINATION", "VERIFIED_BY_OTHER_MEANS",
                "Bank letter checked against the registry extract.",
                Instant.now().plus(7, ChronoUnit.DAYS));

        // The entity is the account and not the override, so that "what has been waived for
        // this person" stays one query after the overrides themselves have cascaded away with
        // an erasure.
        Integer rows = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE action = 'compliance.override_granted'"
                                + " AND entity_id = ?",
                        Integer.class,
                        creator.id());
        assertThat(rows).isEqualTo(1);
    }

    private Map<String, Object> grant(
            UUID subject, String requirement, String reason, String note, Instant expiresAt) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/v1/admin/accounts/%s/compliance-overrides".formatted(subject),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "requirement", requirement,
                                "reason", reason,
                                "note", note,
                                "expiresAt", expiresAt.toString()),
                        authorised(admin().accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> revoke(UUID subject, String overrideId) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/v1/admin/accounts/%s/compliance-overrides/%s".formatted(subject, overrideId),
                HttpMethod.DELETE,
                new HttpEntity<>(authorised(admin().accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> history(UUID subject) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/v1/admin/accounts/%s/compliance-overrides".formatted(subject),
                HttpMethod.GET,
                new HttpEntity<>(authorised(admin().accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders authorised(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private record Account(EmailAddress email, String accessToken, UUID id) {
    }

    /**
     * The bootstrap administrator, issued a token rather than signed in.
     *
     * <p>Signing in would spend one of the five attempts per address that this address is
     * allowed, and a dozen suites share it — {@code AccessTokenIssuer} is how every one of them
     * avoids being the suite that used the last one.
     */
    private Account admin() {
        if (admin != null) {
            return admin;
        }
        EmailAddress email = EmailAddress.of(ADMIN_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Administrator"),
                    String.class);
        }
        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        admin = new Account(email, tokenFor(id), id);
        return admin;
    }

    /** An ordinary account, with an address nothing else in the build takes. */
    private Account account() {
        EmailAddress email =
                EmailAddress.of("compliance-subject-%d@example.com".formatted(SEQUENCE.incrementAndGet()));
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);
        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account(email, tokenFor(id), id);
    }

    private String tokenFor(UUID id) {
        return tokens.issue(
                        id,
                        UUID.randomUUID(),
                        new AccessTokenIssuer.AccountStanding(true, false),
                        false,
                        Instant.now())
                .value();
    }
}
