package az.ideanest.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AD-16's job severity: being due is not being late — issue #405.
 *
 * <p><strong>What was wrong.</strong> Anything at all past its next attempt was graded
 * {@code DEGRADED}, so ten of the nineteen jobs on a healthy platform rendered as "worth
 * looking at" with the detail "0 minutes late" — in the same colour as the two that were
 * six and nine thousand minutes behind. A scheduler picks a job up in the seconds after it
 * falls due, so every healthy job spends part of its cycle in exactly that state; the amber
 * carried no information, and a dashboard whose majority is amber for no stated reason is
 * how the row that matters gets missed.
 *
 * <p>The three tests below are the three bands, and {@link #dueRightNowIsHealthy()} is the
 * one the issue is about. {@link #theThresholdsMustGoLateThenStale()} pins the ordering: a
 * configuration where the amber threshold is not below the red one would show red before
 * amber, and the screen would never draw the distinction the two thresholds exist for.
 *
 * <p>Rows are written into {@code scheduled_jobs} with SQL. The scheduler's own registration
 * writes {@code next_attempt_at = now()}, so the only way to state "due two minutes ago"
 * is to write it, and driving a real job would make a test about a threshold depend on how
 * long the job takes.
 */
class SystemHealthJobGradingTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-long-enough-password";

    /** The single address {@code application-test.yml} lists as a moderator. */
    private static final String MODERATOR_EMAIL = "moderator@ideanest.test";

    private static String accessToken;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void removeTestJobs() {
        new JdbcTemplate(dataSource).update("DELETE FROM scheduled_jobs WHERE name LIKE 'health-grading-%'");
    }

    @Test
    @DisplayName("a job that has just fallen due is healthy, not worth looking at")
    void dueRightNowIsHealthy() {
        String name = job("due-now", Instant.now().minusSeconds(2));

        Map<String, Object> graded = jobNamed(name);

        /*
         * The whole of #405 §3. Two seconds past due is the ordinary state of a scheduler
         * between the trigger firing and the row being claimed, and it renders as "0
         * minutes late" — so grading it amber put a warning on the screen that its own
         * detail line contradicted.
         */
        assertThat(graded).containsEntry("status", "HEALTHY");
        assertThat(((Number) graded.get("overdueBySeconds")).longValue()).isLessThan(60);
    }

    @Test
    @DisplayName("a job late by more than the threshold is worth looking at")
    void lateEnoughIsDegraded() {
        String name = job("late", Instant.now().minus(Duration.ofMinutes(5)));

        // Above the minute the screen renders in and below the quarter of an hour that
        // means the scheduler is not running at all.
        assertThat(jobNamed(name)).containsEntry("status", "DEGRADED");
    }

    @Test
    @DisplayName("a job late by hours is critical")
    void staleIsCritical() {
        String name = job("stale", Instant.now().minus(Duration.ofHours(4)));

        assertThat(jobNamed(name)).containsEntry("status", "CRITICAL");
    }

    @Test
    @DisplayName("the thresholds go late, then stale")
    void theThresholdsMustGoLateThenStale() {
        // Amber before red, the same rule the queue depths already enforce one field up.
        // Reversed, the screen would show red before amber and never draw the distinction
        // the two thresholds exist to draw.
        assertThatThrownBy(() -> new PlatformProperties.Health(
                        100, 1000, Duration.ofMinutes(30), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("late, then stale");
    }

    @Test
    @DisplayName("the defaults put the amber threshold at the minute the screen renders in")
    void theDefaultsAgreeWithWhatIsRendered() {
        PlatformProperties.Health defaults = PlatformProperties.Health.defaults();

        // The console prints lateness in whole minutes. A threshold below a minute would
        // let "0 minutes late" mean amber again, which is exactly the sentence #405 is
        // about.
        assertThat(defaults.lateJobAfter()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.staleJobAfter()).isGreaterThan(defaults.lateJobAfter());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** One row in {@code scheduled_jobs}, due whenever the test says. */
    private String job(String suffix, Instant dueAt) {
        String name = "health-grading-" + suffix;
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO scheduled_jobs (name, state, next_attempt_at)
                        VALUES (?, 'READY', ?)
                        ON CONFLICT (name) DO UPDATE SET state = 'READY', next_attempt_at = EXCLUDED.next_attempt_at
                        """,
                        name,
                        java.sql.Timestamp.from(dueAt));
        return name;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jobNamed(String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(staffToken());

        ResponseEntity<Map<String, Object>> snapshot = rest.exchange(
                "/v1/admin/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        return ((List<Map<String, Object>>) snapshot.getBody().get("jobs"))
                .stream()
                        .filter(job -> name.equals(job.get("name")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("The health snapshot did not mention " + name));
    }

    /**
     * The one account this suite's configuration treats as platform staff.
     *
     * <p>Minted rather than signed in for: a dozen suites share this address and
     * {@code sign-ins-per-email} is left at its real value of five.
     */
    private String staffToken() {
        if (accessToken != null) {
            return accessToken;
        }
        EmailAddress email = EmailAddress.of(MODERATOR_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Moderator"),
                    String.class);
        }

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        accessToken = tokens.issue(
                        id,
                        UUID.randomUUID(),
                        new AccessTokenIssuer.AccountStanding(true, false),
                        false,
                        Instant.now())
                .value();
        return accessToken;
    }
}
