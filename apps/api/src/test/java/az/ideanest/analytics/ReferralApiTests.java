package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.analytics.application.PledgeConfirmed;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two endpoints, over HTTP: recording a visit and reading §4.7's CD-03.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #anybodyMayRecordAVisit()} — the capture endpoint takes no credential,
 *       because the visits that decide an attribution happen before anybody has an
 *       account. Every other assertion about it exists because of that one.
 *   <li>{@link #aVisitToACampaignNobodyHasAnnouncedIsNotFound()} — an unauthenticated
 *       write must not be an oracle for which identifiers exist.
 *   <li>{@link #aStrangerIsToldTheCampaignDoesNotExist()} — a campaign's marketing
 *       performance is competitive information, so the refusal is a 404 rather than a
 *       403.
 *   <li>{@link #theReportNamesNoBacker()} — the guarantee the schema makes, checked at
 *       the surface a creator actually reads.
 * </ul>
 */
class ReferralApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    private static final String AGGREGATE = "pledge";

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM outbox_events");
        // Touches and attributions cascade from the campaign.
        jdbc.update("DELETE FROM projects WHERE slug LIKE 'referral-api-%'");
    }

    // ------------------------------------------------------------------
    // Recording a visit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("anybody may record a visit, and is given a token to send next time")
    void anybodyMayRecordAVisit() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response = visit(campaign.id(), null, visitBody("SOCIAL", "twitter"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("visitorToken", "expiresAt");
        assertThat((String) response.getBody().get("visitorToken")).isNotBlank();
    }

    @Test
    @DisplayName("a token that is sent back is the token that comes back")
    void aTokenSentBackIsTheTokenThatComesBack() {
        Campaign campaign = liveCampaign();
        String token = tokenFrom(visit(campaign.id(), null, visitBody("SOCIAL", "twitter")));

        Map<String, Object> second = visitBody("EMAIL", "newsletter");
        second.put("visitorToken", token);

        assertThat(tokenFrom(visit(campaign.id(), null, second))).isEqualTo(token);
    }

    @Test
    @DisplayName("a token this service did not mint is replaced rather than refused")
    void aTokenWeDidNotMintIsReplaced() {
        // A client holding a value from before some future format change must not be
        // broken by an endpoint whose whole purpose is to be called by clients we do
        // not control. There is nothing to protect: a token is a bucket for visits.
        Campaign campaign = liveCampaign();
        Map<String, Object> body = visitBody("SOCIAL", "twitter");
        body.put("visitorToken", "not-a-token");

        ResponseEntity<Map<String, Object>> response = visit(campaign.id(), null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) response.getBody().get("visitorToken")).isNotEqualTo("not-a-token");
    }

    @Test
    @DisplayName("a visit to a campaign nobody has announced is not found")
    void aVisitToACampaignNobodyHasAnnouncedIsNotFound() {
        Campaign draft = draftCampaign();

        ResponseEntity<Map<String, Object>> response = visit(draft.id(), null, visitBody("SOCIAL", "twitter"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");
    }

    @Test
    @DisplayName("a visit to a campaign that does not exist is the same answer")
    void aVisitToACampaignThatDoesNotExistIsTheSameAnswer() {
        ResponseEntity<Map<String, Object>> response =
                visit(UUID.randomUUID(), null, visitBody("SOCIAL", "twitter"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");
    }

    @Test
    @DisplayName("a direct visit that also names a source is refused, not quietly trimmed")
    void aDirectVisitThatNamesASourceIsRefused() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response = visit(campaign.id(), null, visitBody("DIRECT", "twitter"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "REFERRAL_SOURCE_INVALID");
    }

    @Test
    @DisplayName("a visit that names no channel at all is refused")
    void aVisitWithNoChannelIsRefused() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response =
                visit(campaign.id(), null, new LinkedHashMap<>(Map.of("source", "twitter")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // Reading the report
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the report requires a token")
    void theReportRequiresAToken() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/v1/projects/" + campaign.id() + "/referrers", HttpMethod.GET, null, OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a stranger is told the campaign does not exist")
    void aStrangerIsToldTheCampaignDoesNotExist() {
        Campaign campaign = liveCampaign();
        Account stranger = account();

        ResponseEntity<Map<String, Object>> response = referrers(campaign.id(), stranger.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");
    }

    @Test
    @DisplayName("the creator reads the sources, their value, and their share")
    void theCreatorReadsTheSourcesTheirValueAndTheirShare() {
        Campaign campaign = liveCampaign();
        attribute(campaign.id(), "SOCIAL", "twitter", "75.00");
        attribute(campaign.id(), "EMAIL", "newsletter", "25.00");

        ResponseEntity<Map<String, Object>> response = referrers(campaign.id(), campaign.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("currency", "AZN");
        assertThat(((Number) body.get("pledgeCount")).longValue()).isEqualTo(2);
        // §10.3: money is an object with a string amount, never a JSON number.
        assertThat(body.get("value")).isEqualTo(Map.of("amount", "100.00", "currency", "AZN"));

        List<?> sources = (List<?>) body.get("sources");
        assertThat(sources).hasSize(2);
        Map<String, Object> first = asObject(sources.get(0));
        assertThat(first).containsEntry("channel", "SOCIAL").containsEntry("source", "twitter");
        assertThat(first.get("value")).isEqualTo(Map.of("amount", "75.00", "currency", "AZN"));
        assertThat(new BigDecimal(first.get("share").toString())).isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    @DisplayName("the report names no backer, anywhere in the body")
    void theReportNamesNoBacker() {
        Campaign campaign = liveCampaign();
        UUID backer = attribute(campaign.id(), "SOCIAL", "twitter", "75.00");

        String body = rest.exchange(
                        "/v1/projects/" + campaign.id() + "/referrers",
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(campaign.accessToken())),
                        String.class)
                .getBody();

        // Asserted against the whole serialised body rather than against a field,
        // because the guarantee is that there is no field: referral_attributions has
        // nowhere to put a backer, and this is that showing through at the surface a
        // creator reads.
        assertThat(body).doesNotContain(backer.toString());
        assertThat(body).doesNotContain("backer");
    }

    @Test
    @DisplayName("the report is never stored by a cache")
    void theReportIsNeverStoredByACache() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response = referrers(campaign.id(), campaign.accessToken());

        // One campaign's marketing performance, belonging to the account that asked.
        // A shared cache holding it is a shared cache able to serve it to somebody
        // else, which is the difference between this and the public reads next door.
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("a campaign with nothing attributed reports no total rather than zero")
    void aCampaignWithNothingAttributedReportsNoTotal() {
        Campaign campaign = liveCampaign();

        Map<String, Object> body = referrers(campaign.id(), campaign.accessToken()).getBody();

        assertThat(((Number) body.get("pledgeCount")).longValue()).isZero();
        assertThat((List<?>) body.get("sources")).isEmpty();
        // Absent, not zero: "this campaign has taken nothing yet" and "this campaign
        // has taken zero manat" are different sentences and only the first is true.
        assertThat(body).doesNotContainKey("value").doesNotContainKey("currency");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A registered, signed-in account. */
    private record Account(String accessToken, UUID id) {
    }

    /** A campaign, and a token for the account that owns it. */
    private record Campaign(UUID id, String accessToken) {
    }

    private Account account() {
        EmailAddress email = EmailAddress.of("referral-api" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"),
                        jsonHeaders()),
                OBJECT);

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account((String) signedIn.getBody().get("accessToken"), id);
    }

    private Campaign liveCampaign() {
        return campaign("LIVE");
    }

    private Campaign draftCampaign() {
        return campaign("DRAFT");
    }

    private Campaign campaign(String state) {
        Account creator = account();
        String slug = "referral-api-" + SEQUENCE.incrementAndGet();
        UUID projectId =
                Campaigns.seed(dataSource, creator.id(), slug).state(state).insert();
        return new Campaign(projectId, creator.accessToken());
    }

    /** One attributed pledge from one source. Returns the backer, so a test can look for it. */
    private UUID attribute(UUID projectId, String channel, String source, String amount) {
        Map<String, Object> body = visitBody(channel, source);
        Account backer = account();
        visit(projectId, backer.accessToken(), body);

        UUID pledgeId = UUID.randomUUID();
        PledgeConfirmed event = new PledgeConfirmed(
                pledgeId,
                projectId,
                backer.id(),
                Money.of(new BigDecimal(amount), "AZN"),
                null,
                java.time.Instant.now());
        new TransactionTemplate(transactions)
                .executeWithoutResult(status ->
                        outbox.record(AGGREGATE, pledgeId, PledgeConfirmed.EVENT_TYPE, event));
        relay.run();
        return backer.id();
    }

    private static Map<String, Object> visitBody(String channel, String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("source", source);
        return body;
    }

    private ResponseEntity<Map<String, Object>> visit(UUID projectId, String accessToken, Object body) {
        HttpHeaders headers = accessToken == null ? jsonHeaders() : bearer(accessToken);
        return rest.exchange(
                "/v1/projects/" + projectId + "/referral-visits",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                OBJECT);
    }

    private ResponseEntity<Map<String, Object>> referrers(UUID projectId, String accessToken) {
        return rest.exchange(
                "/v1/projects/" + projectId + "/referrers",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                OBJECT);
    }

    /**
     * One element of a JSON array, as the object it is.
     *
     * <p>The cast is unchecked because the body was deserialised into a
     * {@code Map<String, Object>}; confined to one method so that the suppression is
     * one line rather than one per assertion.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object element) {
        return (Map<String, Object>) element;
    }

    private static String tokenFrom(ResponseEntity<Map<String, Object>> response) {
        return (String) response.getBody().get("visitorToken");
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
