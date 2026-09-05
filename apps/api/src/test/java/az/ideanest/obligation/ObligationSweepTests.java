package az.ideanest.obligation;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.obligation.ObligationProperties;
import az.ideanest.obligation.application.ObligationSweepJob;
import az.ideanest.obligation.application.UpdateObligations;
import az.ideanest.obligation.domain.ObligationState;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sweep, the queue, and the surfaces that draw them — issue #437.
 *
 * <p>{@code UpdateObligationTests} owns the rule; this owns the wiring. What it asserts is that
 * the sweep claims each cycle once against a real row, that the escalation reaches a moderator,
 * that resolving it needs the capability and the note, and that the public pages get a state they
 * can render — the four things a unit test on the entity cannot say anything about.
 *
 * <p>The clock is moved rather than waited on. {@code AdjustableClock}'s own comment argues why: a
 * sleeping test is slow and, worse, flaky exactly when the machine is busy, which is on CI in the
 * run nobody is watching. The sweep's schedule is {@code -} in the test profile so that nothing
 * fires in the background of a suite that is about to assert on the very rows it would act on.
 */
class ObligationSweepTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";
    private static final String ADMIN_EMAIL = "moderator@ideanest.test";

    private UUID creatorId;
    private UUID projectId;
    private String adminToken;
    private UUID adminId;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private UpdateObligations obligations;

    @Autowired
    private ObligationSweepJob sweep;

    @Autowired
    private ObligationProperties properties;

    @BeforeEach
    void seed() {
        clock.freeze();
        creatorId = Campaigns.creator(dataSource, "obligation-creator-" + SEQUENCE.incrementAndGet());
        projectId = Campaigns.seed(dataSource, creatorId, "obligation-campaign-" + SEQUENCE.get())
                .state("SUCCESSFUL")
                .insert();
    }

    @AfterEach
    void clear() {
        // Obligations first: they reference the campaigns Campaigns.clear removes, and the
        // cascade would take them anyway — doing it explicitly keeps the order readable and
        // stops a later change to that helper silently deciding this suite's teardown.
        new JdbcTemplate(dataSource).update("DELETE FROM update_obligations");
        Campaigns.clear(dataSource);
        clock.reset();
    }

    @Test
    @DisplayName("a campaign says nothing about an obligation until one is opened")
    void noObligationUntilTheCampaignCloses() {
        ResponseEntity<String> answer =
                rest.getForEntity("/v1/projects/%s/update-obligation".formatted(projectId), String.class);

        // 204 and not 404: a campaign that is live, was unsuccessful, or closed before this
        // mechanism existed has no clock, and none of those is a failure state for the page to
        // draw.
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("the sweep warns once before the month is up, and does not warn twice")
    void theWarningIsSentOncePerCycle() {
        Instant closed = clock.instant();
        obligations.open(projectId, creatorId, closed);

        // A fortnight in: nothing is owed yet.
        clock.advance(Duration.ofDays(14));
        assertThat(sweep.sweep()).isZero();

        // Inside the lead. One warning.
        clock.advance(properties.interval().minus(properties.reminderLead()).minus(Duration.ofDays(14)));
        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(state()).isEqualTo(ObligationState.DUE_SOON.name());

        // The next morning, and the one after. The claim is the due date, so the cycle is
        // already spent.
        clock.advance(Duration.ofDays(1));
        assertThat(sweep.sweep()).isZero();
        clock.advance(Duration.ofDays(1));
        assertThat(sweep.sweep()).isZero();
    }

    @Test
    @DisplayName("a lapse escalates once and stays one row however many mornings pass")
    void aLapseEscalatesOnce() {
        obligations.open(projectId, creatorId, clock.instant());

        clock.advance(properties.interval().minus(properties.reminderLead()));
        sweep.sweep(); // the warning

        clock.advance(properties.reminderLead());
        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(state()).isEqualTo(ObligationState.NEVER_UPDATED.name());
        assertThat(queue()).hasSize(1);

        // A fortnight of daily sweeps over a campaign that stays silent. #437's test by name: a
        // moderator's queue must not fill with one row per morning per silent campaign.
        for (int day = 0; day < 14; day++) {
            clock.advance(Duration.ofDays(1));
            assertThat(sweep.sweep()).isZero();
        }
        assertThat(queue()).hasSize(1);
    }

    @Test
    @DisplayName("an update clears the lapse and the campaign page says so")
    void anUpdateClearsTheLapse() {
        obligations.open(projectId, creatorId, clock.instant());

        clock.advance(properties.interval());
        sweep.sweep();
        assertThat(state()).isEqualTo(ObligationState.NEVER_UPDATED.name());

        obligations.recordUpdate(projectId, clock.instant());

        assertThat(state()).isEqualTo(ObligationState.CURRENT.name());
        // The case stays in the queue: the moderator has still not looked, and a creator who
        // posts after being escalated has not made the escalation unhappen.
        assertThat(queue()).hasSize(1);
    }

    @Test
    @DisplayName("fulfilment completing stops the sweep touching it again")
    void completionStopsTheSweep() {
        obligations.open(projectId, creatorId, clock.instant());
        obligations.close(projectId, clock.instant());

        clock.advance(properties.interval().plus(Duration.ofDays(30)));

        assertThat(sweep.sweep()).isZero();
        assertThat(state()).isEqualTo(ObligationState.COMPLETE.name());
        assertThat(queue()).isEmpty();
    }

    @Test
    @DisplayName("a moderator resolves the escalation, and the note is required")
    void resolvingTheEscalation() {
        obligations.open(projectId, creatorId, clock.instant());
        clock.advance(properties.interval());
        sweep.sweep();

        ResponseEntity<Map<String, Object>> refused = rest.exchange(
                "/v1/admin/update-obligations/%s/resolve".formatted(projectId),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "   "), authorised(adminToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> resolved = rest.exchange(
                "/v1/admin/update-obligations/%s/resolve".formatted(projectId),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Emailed the creator; they are shipping in April."),
                        authorised(adminToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("resolvedAt")).isNotNull();
        assertThat(queue()).isEmpty();

        // Resolving closes the case and not the obligation: the campaign is still late, and the
        // page still says so. That is the whole difference between a queue and a snooze button.
        assertThat(state()).isEqualTo(ObligationState.NEVER_UPDATED.name());

        // And a second attempt is a 409, because it is the same to the moderator as a colleague
        // having got there first: reload the queue.
        ResponseEntity<Map<String, Object>> again = rest.exchange(
                "/v1/admin/update-obligations/%s/resolve".formatted(projectId),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Again."), authorised(adminToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("NO_LAPSE_TO_RESOLVE");
    }

    @Test
    @DisplayName("the queue needs the moderation capability")
    void theQueueIsNotPublic() {
        ResponseEntity<String> refused = rest.exchange(
                "/v1/admin/update-obligations",
                HttpMethod.GET,
                new HttpEntity<>(authorised(tokenFor(creatorId))),
                String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a creator's history is public, and counts what is late now")
    void theCreatorHistoryIsPublic() {
        obligations.open(projectId, creatorId, clock.instant());
        clock.advance(properties.interval());
        sweep.sweep();

        ResponseEntity<Map<String, Object>> history = rest.exchange(
                "/v1/creators/%s/update-obligations".formatted(creatorId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Unauthenticated on purpose. §22.3's mechanism is that the history is visible to the
        // person deciding whether to back this creator again, and that person has not signed in.
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody().get("lapsedCount")).isEqualTo(1);
        assertThat((List<?>) history.getBody().get("obligations")).hasSize(1);
    }

    @Test
    @DisplayName("the same history is addressed by slug, because that is what the profile has")
    void theCreatorHistoryIsAlsoAddressedBySlug() {
        obligations.open(projectId, creatorId, clock.instant());
        clock.advance(properties.interval());
        sweep.sweep();

        String slug = new JdbcTemplate(dataSource)
                .queryForObject("SELECT slug FROM users WHERE id = ?", String.class, creatorId);

        ResponseEntity<Map<String, Object>> history = rest.exchange(
                "/v1/users/%s/update-obligations".formatted(slug),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // §4.2's profile projection deliberately carries no identifier, so a page that has just
        // rendered somebody's profile knows their address and nothing else.
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody().get("lapsedCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("a slug nobody holds answers an empty history, not a 404")
    void anUnknownSlugIsNotDistinguishable() {
        ResponseEntity<Map<String, Object>> history = rest.exchange(
                "/v1/users/nobody-holds-this-slug/update-obligations",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // A 404 here would make this endpoint a way to tell an unknown slug from a closed account
        // from outside — the leak the profile's own 404 goes to some trouble to prevent. A
        // creator with no closed campaigns and a slug nobody holds both have nothing to disclose.
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) history.getBody().get("obligations")).isEmpty();
        assertThat(history.getBody().get("lapsedCount")).isEqualTo(0);
    }

    private String state() {
        ResponseEntity<Map<String, Object>> answer = rest.exchange(
                "/v1/projects/%s/update-obligation".formatted(projectId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) answer.getBody().get("state");
    }

    private List<?> queue() {
        ResponseEntity<Map<String, Object>> answer = rest.exchange(
                "/v1/admin/update-obligations",
                HttpMethod.GET,
                new HttpEntity<>(authorised(adminToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<?>) answer.getBody().get("escalations");
    }

    private static HttpHeaders authorised(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    /**
     * The bootstrap administrator, issued a token rather than signed in.
     *
     * <p>Signing in would spend one of the five attempts per address this address is allowed, and
     * a dozen suites share it.
     */
    private String adminToken() {
        if (adminToken != null) {
            return adminToken;
        }
        EmailAddress email = EmailAddress.of(ADMIN_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Administrator"),
                    String.class);
        }
        adminId = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        adminToken = tokenFor(adminId);
        return adminToken;
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
