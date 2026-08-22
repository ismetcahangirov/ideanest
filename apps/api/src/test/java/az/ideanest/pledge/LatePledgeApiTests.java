package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §4.5's PL-16 and §4.8's PM-23 (#81): a campaign that goes on taking pledges after it
 * closed.
 *
 * <p>The tests that carry the design:
 *
 * <ul>
 *   <li>{@link #aLatePledgeIsRecordedAsALateOne()} — the whole reason the feature is
 *       not just "accept a pledge in one more state". A campaign's two totals have to
 *       stay apart, and {@code is_late_pledge} is what keeps them apart.
 *   <li>{@link #switchingTheFeatureOffStopsThePledgesImmediately()} — the reason
 *       enabling and the window are two facts rather than one: a creator who runs out
 *       of stock needs a switch, not a transition they cannot undo.
 *   <li>{@link #theWindowIsWhatTheRefusalNames()} — a backer refused after a
 *       late-pledge window closed must not be told about a deadline months earlier.
 *   <li>{@link #aWindowBeyondThePlatformsBoundIsRefused()} — a campaign still taking
 *       money nine months after it closed has customers, not backers.
 *   <li>{@link #closingTheWindowStartsFulfilment()} — and refuses the next pledge.
 * </ul>
 *
 * <p><strong>The fixture reaches {@code COLLECTING} by writing the row</strong>, and
 * {@code Campaigns.collecting} says why there is no honest alternative: the edge into
 * it is epic #59's batched collection, which is blocked on choosing a payment provider.
 * Everything after that point is exercised through the API.
 */
class LatePledgeApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM pledges");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
    }

    // ------------------------------------------------------------------
    // Opening the window
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a creator opens a late-pledge window and the campaign starts taking pledges again")
    void aCreatorOpensTheWindow() {
        Account creator = account("late-open");
        UUID project = collectingCampaign(creator, true);
        Instant closes = Instant.now().plus(Duration.ofDays(14));

        ResponseEntity<Map<String, Object>> opened = open(project, creator, closes);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getBody()).containsEntry("state", "LATE_PLEDGE");
        assertThat(opened.getBody().get("latePledgeEndsAt")).isNotNull();

        assertThat(draft(project, account("late-open-backer")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a campaign that has not enabled late pledges cannot open a window")
    void theFeatureHasToBeSwitchedOnFirst() {
        Account creator = account("late-off");
        UUID project = collectingCampaign(creator, false);

        ResponseEntity<Map<String, Object>> refused = open(project, creator, Instant.now().plus(Duration.ofDays(7)));

        // A 409 rather than a 400: the request is well formed and the creator is
        // entitled to make it. What is missing is a decision, and the code names the
        // switch that takes it.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code", "LATE_PLEDGES_NOT_ENABLED");
    }

    @Test
    @DisplayName("a live campaign cannot open a late-pledge window")
    void theWindowOpensFromCollectingOnly() {
        Account creator = account("late-live");
        UUID project = project(creator);
        enableLatePledges(project, creator);
        Campaigns.launch(dataSource, project);

        ResponseEntity<Map<String, Object>> refused = open(project, creator, Instant.now().plus(Duration.ofDays(7)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code", "PROJECT_TRANSITION_NOT_ALLOWED");
    }

    @Test
    @DisplayName("a window that ends in the past is refused")
    void aWindowInThePastIsRefused() {
        Account creator = account("late-past");
        UUID project = collectingCampaign(creator, true);

        ResponseEntity<Map<String, Object>> refused = open(project, creator, Instant.now().minusSeconds(60));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(meta(refused.getBody())).containsEntry("field", "endsAt");
    }

    @Test
    @DisplayName("a window beyond the platform's bound is refused")
    void aWindowBeyondThePlatformsBoundIsRefused() {
        Account creator = account("late-long");
        UUID project = collectingCampaign(creator, true);

        // Ninety days by default. A campaign still taking money nine months after it
        // closed has customers rather than backers, and no stock to sell them.
        ResponseEntity<Map<String, Object>> refused = open(project, creator, Instant.now().plus(Duration.ofDays(200)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(meta(refused.getBody())).containsEntry("field", "endsAt");
    }

    @Test
    @DisplayName("somebody who is not the creator cannot open the window")
    void onlyTheCreatorOpensTheWindow() {
        Account creator = account("late-guard");
        UUID project = collectingCampaign(creator, true);

        ResponseEntity<Map<String, Object>> refused =
                open(project, account("late-stranger"), Instant.now().plus(Duration.ofDays(7)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // What a late pledge is
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a late pledge is recorded as a late one")
    void aLatePledgeIsRecordedAsALateOne() {
        Account creator = account("late-flag");
        UUID project = collectingCampaign(creator, true);
        open(project, creator, Instant.now().plus(Duration.ofDays(7)));

        ResponseEntity<Map<String, Object>> created = draft(project, account("late-flag-backer"));
        UUID pledge = UUID.fromString((String) created.getBody().get("id"));

        // The flag is the whole feature: §5.1 judged this campaign against its goal at
        // its deadline, and money taken afterwards must not silently join the number
        // that decision was made from.
        assertThat(isLate(pledge)).isTrue();
    }

    @Test
    @DisplayName("a pledge taken while the campaign was running is not")
    void anOrdinaryPledgeIsNotLate() {
        Account creator = account("late-normal");
        UUID project = project(creator);
        Campaigns.launch(dataSource, project);

        UUID pledge = UUID.fromString(
                (String) draft(project, account("late-normal-backer")).getBody().get("id"));

        assertThat(isLate(pledge)).isFalse();
    }

    @Test
    @DisplayName("switching the feature off stops the pledges immediately")
    void switchingTheFeatureOffStopsThePledgesImmediately() {
        Account creator = account("late-switch");
        UUID project = collectingCampaign(creator, true);
        open(project, creator, Instant.now().plus(Duration.ofDays(7)));
        assertThat(draft(project, account("late-switch-first")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        patch(project, creator, Map.of("latePledgeEnabled", false));

        // No transition, and no window to edit: a creator who has run out of stock
        // needs the pledges to stop on the next request, and the campaign stays in
        // LATE_PLEDGE until they decide to start delivering.
        ResponseEntity<Map<String, Object>> refused = draft(project, account("late-switch-second"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code", "PROJECT_NOT_LIVE");
        assertThat(state(project)).isEqualTo("LATE_PLEDGE");
    }

    @Test
    @DisplayName("the refusal names the late-pledge window rather than the funding deadline")
    void theWindowIsWhatTheRefusalNames() {
        Account creator = account("late-expired");
        UUID project = collectingCampaign(creator, true);
        Instant closes = Instant.now().plus(Duration.ofDays(3));
        open(project, creator, closes);

        // Moved by hand rather than by waiting three days. What is asserted is which
        // date the refusal reports, and the campaign's funding deadline is a day in the
        // past -- so a client rendering the wrong one would tell a backer the campaign
        // closed before it opened its window.
        new JdbcTemplate(dataSource)
                .update("UPDATE projects SET late_pledge_ends_at = now() - interval '1 hour' WHERE id = ?", project);

        ResponseEntity<Map<String, Object>> refused = draft(project, account("late-expired-backer"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code", "PROJECT_NOT_LIVE");
        assertThat(meta(refused.getBody())).containsEntry("state", "LATE_PLEDGE");
        assertThat(meta(refused.getBody()).get("deadline"))
                .as("the date a backer was counting down to is the window's, not the campaign's")
                .isNotNull();
    }

    @Test
    @DisplayName("closing the window starts fulfilment and refuses the next pledge")
    void closingTheWindowStartsFulfilment() {
        Account creator = account("late-close");
        UUID project = collectingCampaign(creator, true);
        open(project, creator, Instant.now().plus(Duration.ofDays(7)));

        ResponseEntity<Map<String, Object>> closed = exchange(
                "/v1/projects/" + project + "/late-pledges/close", HttpMethod.POST, creator.accessToken(), null);

        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody()).containsEntry("state", "FULFILLING");
        assertThat(closed.getBody().get("latePledgeEndsAt"))
                .as("the window this campaign accepted late pledges in is a true statement about it")
                .isNotNull();

        assertThat(draft(project, account("late-close-backer")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("the public page carries the window, so a visitor arriving late knows there is still a way in")
    void thePublicPageCarriesTheWindow() {
        Account creator = account("late-public");
        UUID project = collectingCampaign(creator, true);
        open(project, creator, Instant.now().plus(Duration.ofDays(7)));

        Map<String, Object> page = publicPage(creator, project);

        assertThat(page).containsEntry("state", "LATE_PLEDGE").containsEntry("latePledgeEnabled", true);
        assertThat(page.get("latePledgeEndsAt")).isNotNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(String accessToken, UUID id, String slug) {
    }

    private Account account(String prefix) {
        EmailAddress email = EmailAddress.of(prefix + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        var user = users.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        return new Account((String) signedIn.getBody().get("accessToken"), user.getId(), user.getSlug());
    }

    private UUID project(Account creator) {
        ResponseEntity<Map<String, Object>> created = exchange(
                "/v1/projects",
                HttpMethod.POST,
                creator.accessToken(),
                Map.of("title", "A campaign with a second wind " + SEQUENCE.incrementAndGet()));
        return UUID.fromString((String) created.getBody().get("id"));
    }

    /** A campaign that closed above goal and is being collected — see {@code Campaigns.collecting}. */
    private UUID collectingCampaign(Account creator, boolean latePledgesEnabled) {
        UUID project = project(creator);
        if (latePledgesEnabled) {
            enableLatePledges(project, creator);
        }
        Campaigns.collecting(dataSource, project);
        return project;
    }

    private void enableLatePledges(UUID project, Account creator) {
        patch(project, creator, Map.of("latePledgeEnabled", true));
    }

    private ResponseEntity<Map<String, Object>> patch(UUID project, Account creator, Map<String, Object> body) {
        return exchange("/v1/projects/" + project, HttpMethod.PATCH, creator.accessToken(), body);
    }

    private ResponseEntity<Map<String, Object>> open(UUID project, Account caller, Instant endsAt) {
        return exchange(
                "/v1/projects/" + project + "/late-pledges",
                HttpMethod.POST,
                caller.accessToken(),
                Map.of("endsAt", endsAt.toString()));
    }

    private ResponseEntity<Map<String, Object>> draft(UUID project, Account backer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", project.toString());
        body.put("contribution", Map.of("amount", "25.00", "currency", "AZN"));

        HttpHeaders headers = bearer(backer.accessToken());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return rest.exchange(
                "/v1/pledges/draft",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> publicPage(Account creator, UUID project) {
        String slug = new JdbcTemplate(dataSource)
                .queryForObject("SELECT slug FROM projects WHERE id = ?", String.class, project);
        return rest.exchange(
                        "/v1/projects/" + creator.slug() + "/" + slug,
                        HttpMethod.GET,
                        new HttpEntity<>(null, jsonHeaders()),
                        new ParameterizedTypeReference<Map<String, Object>>() {})
                .getBody();
    }

    private boolean isLate(UUID pledgeId) {
        return Boolean.TRUE.equals(new JdbcTemplate(dataSource)
                .queryForObject("SELECT is_late_pledge FROM pledges WHERE id = ?", Boolean.class, pledgeId));
    }

    private String state(UUID project) {
        return new JdbcTemplate(dataSource).queryForObject("SELECT state FROM projects WHERE id = ?", String.class, project);
    }

    private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, String token, Object body) {
        return rest.exchange(
                path,
                method,
                new HttpEntity<>(body, token == null ? jsonHeaders() : bearer(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> body) {
        return (Map<String, Object>) body.get("meta");
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
}
