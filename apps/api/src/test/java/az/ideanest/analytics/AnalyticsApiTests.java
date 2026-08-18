package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.analytics.application.AnalyticsRollupService;
import az.ideanest.analytics.domain.RollupWindow;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/**
 * {@code GET /v1/projects/{id}/analytics} over HTTP: §4.7's CD-01 and CD-02 as a client
 * actually receives them.
 *
 * <p>{@code AnalyticsRollupTests} owns the arithmetic and asserts it against the
 * database. This class owns the surface, and only the things that can be wrong at the
 * surface while the arithmetic is right:
 *
 * <ul>
 *   <li>{@link #everyAmountCrossesTheWireAsAString()} — CLAUDE.md's money rule and §10.3,
 *       checked against the serialised bytes rather than against a deserialised map. A
 *       {@code Money} that had become a JSON number would still compare equal after
 *       Jackson had read it back, so the only honest assertion is on the text.
 *   <li>{@link #aStrangerIsToldTheCampaignDoesNotExist()} — a campaign's daily takings are
 *       competitive information, so the refusal is a 404 and not a 403.
 *   <li>{@link #theBodyStatesTheCalendarItsDaysBelongTo()} — the timezone decision,
 *       asserted where a client can see it. A dashboard that had to guess which calendar
 *       a {@code day} belongs to would guess the browser's.
 *   <li>{@link #aRangeRunningBackwardsIsRefused()} and
 *       {@link #aRangeLongerThanAYearIsRefused()} — the two refusals, which are the
 *       caller's to fix and therefore 400 rather than a silently corrected answer.
 * </ul>
 *
 * <p>Attributions are written straight in and the rollup driven directly, as
 * {@code AnalyticsRollupTests} does and for the same reason: the event path belongs to
 * {@code ReferralAttributionTests}, and what is on trial here is the response.
 */
class AnalyticsApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    /** Baku is UTC+4, so 09:00 UTC is safely inside the same day in either calendar. */
    private static final String MIDDAY = "T09:00:00Z";

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private AnalyticsRollupService rollups;

    @Autowired
    private AnalyticsAggregationProperties properties;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        clock.reset();
        // Attributions and both rollup tables cascade from the campaign.
        new JdbcTemplate(dataSource).update("DELETE FROM projects WHERE slug LIKE 'analytics-api-%'");
    }

    // ------------------------------------------------------------------
    // Who may read it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the trend requires a token")
    void theTrendRequiresAToken() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/v1/projects/" + campaign.id() + "/analytics", HttpMethod.GET, null, OBJECT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a stranger is told the campaign does not exist")
    void aStrangerIsToldTheCampaignDoesNotExist() {
        Campaign campaign = liveCampaign();
        Account stranger = account();

        ResponseEntity<Map<String, Object>> response = analytics(campaign.id(), stranger.accessToken(), "");

        // Not a 403. What a campaign is raising and how fast is competitive information,
        // and a 403 would confirm the campaign exists to somebody enumerating identifiers.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");
    }

    @Test
    @DisplayName("a campaign that does not exist is the same answer")
    void aCampaignThatDoesNotExistIsTheSameAnswer() {
        Account account = account();

        ResponseEntity<Map<String, Object>> response = analytics(UUID.randomUUID(), account.accessToken(), "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // What the creator reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the creator reads the days, their totals, and the running line")
    void theCreatorReadsTheDaysTheirTotalsAndTheRunningLine() {
        Campaign campaign = liveCampaign();
        attribute(campaign.id(), "2026-03-08" + MIDDAY, "100.00", "SOCIAL", "twitter");
        attribute(campaign.id(), "2026-03-10" + MIDDAY, "50.00", "EMAIL", "newsletter");
        rollUp("2026-03-08", "2026-03-10");

        ResponseEntity<Map<String, Object>> response =
                analytics(campaign.id(), campaign.accessToken(), "?from=2026-03-08&to=2026-03-10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("currency", "AZN");
        assertThat(body.get("computedAt")).isNotNull();

        List<?> days = (List<?>) body.get("days");
        // Two rows for three days: the 9th is absent rather than zero, and the cumulative
        // on the 10th is what lets a chart draw the line across the gap.
        assertThat(days).hasSize(2);
        Map<String, Object> first = asObject(days.get(0));
        assertThat(first).containsEntry("day", "2026-03-08");
        assertThat(first.get("amount")).isEqualTo(Map.of("amount", "100.00", "currency", "AZN"));
        assertThat(first.get("cumulativeAmount")).isEqualTo(Map.of("amount", "100.00", "currency", "AZN"));

        Map<String, Object> second = asObject(days.get(1));
        assertThat(second).containsEntry("day", "2026-03-10");
        assertThat(second.get("amount")).isEqualTo(Map.of("amount", "50.00", "currency", "AZN"));
        assertThat(second.get("cumulativeAmount")).isEqualTo(Map.of("amount", "150.00", "currency", "AZN"));
        assertThat(((Number) second.get("cumulativePledgeCount")).longValue()).isEqualTo(2);

        List<?> channels = (List<?>) second.get("channels");
        assertThat(channels).hasSize(1);
        assertThat(asObject(channels.get(0))).containsEntry("channel", "EMAIL");
    }

    @Test
    @DisplayName("every amount crosses the wire as a string, never as a JSON number")
    void everyAmountCrossesTheWireAsAString() {
        Campaign campaign = liveCampaign();
        // Tenths and fifths: 0.1 + 0.2 is 0.30000000000000004 as a double, so a body
        // carrying these as JSON numbers would say so.
        attribute(campaign.id(), "2026-03-10" + MIDDAY, "0.10", "SOCIAL", "twitter");
        attribute(campaign.id(), "2026-03-10" + MIDDAY, "0.20", "SOCIAL", "twitter");
        rollUp("2026-03-10", "2026-03-10");

        String body = rest.exchange(
                        "/v1/projects/" + campaign.id() + "/analytics?from=2026-03-10&to=2026-03-10",
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(campaign.accessToken())),
                        String.class)
                .getBody();

        // Asserted against the serialised body rather than a deserialised map: Jackson
        // would read a JSON number back into a BigDecimal and the assertion would pass
        // on a response that had already lost the qapik.
        assertThat(body).contains("\"amount\":\"0.30\"");
        assertThat(body).doesNotContain("\"amount\":0.3");
        assertThat(body).doesNotContain("0.30000000000000004");
    }

    @Test
    @DisplayName("the body states the calendar its days belong to")
    void theBodyStatesTheCalendarItsDaysBelongTo() {
        Campaign campaign = liveCampaign();
        // 00:30 on the 11th in Baku, 20:30 on the 10th in UTC — the four-hour window a
        // UTC rollup would file against the previous day.
        attribute(campaign.id(), "2026-03-10T20:30:00Z", "100.00", "SOCIAL", "twitter");
        rollUp("2026-03-10", "2026-03-11");

        Map<String, Object> body = analytics(
                        campaign.id(), campaign.accessToken(), "?from=2026-03-10&to=2026-03-11")
                .getBody();

        // Stated rather than assumed, at the top and on the row. A client that had to
        // guess which calendar a `day` belongs to would guess the browser's, and would
        // move this pledge back to the 10th on a screen in Baku.
        assertThat(body).containsEntry("timeZone", properties.zone().getId());
        List<?> days = (List<?>) body.get("days");
        assertThat(days).hasSize(1);
        assertThat(asObject(days.get(0)))
                .containsEntry("day", "2026-03-11")
                .containsEntry("timeZone", properties.zone().getId());
    }

    @Test
    @DisplayName("a range nobody asked for is the last thirty days in the platform's calendar")
    void aRangeNobodyAskedForIsTheLastThirtyDays() {
        clock.freeze();
        Campaign campaign = liveCampaign();
        LocalDate today = RollupWindow.dayOf(clock.instant(), properties.zone());

        Map<String, Object> body =
                analytics(campaign.id(), campaign.accessToken(), "").getBody();

        // Today in Baku, which is not today in UTC for four hours out of every
        // twenty-four — and the endpoint resolves it from the same property the rollup
        // buckets by rather than from the JVM's default zone.
        assertThat(body).containsEntry("to", today.toString());
        assertThat(body).containsEntry("from", today.minusDays(29).toString());
    }

    @Test
    @DisplayName("a campaign with nothing rolled up reports no total rather than zero")
    void aCampaignWithNothingRolledUpReportsNoTotal() {
        Campaign campaign = liveCampaign();

        Map<String, Object> body = analytics(
                        campaign.id(), campaign.accessToken(), "?from=2026-03-08&to=2026-03-10")
                .getBody();

        assertThat((List<?>) body.get("days")).isEmpty();
        // Absent, not zero: "this campaign took nothing in these days" and "it took zero
        // manat" are different sentences and only the first one is true.
        assertThat(body).doesNotContainKey("currency").doesNotContainKey("computedAt");
    }

    @Test
    @DisplayName("the trend is never stored by a cache")
    void theTrendIsNeverStoredByACache() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response = analytics(campaign.id(), campaign.accessToken(), "");

        // One campaign's daily takings, belonging to the account that asked for them. A
        // shared cache holding this body is a shared cache able to serve it to somebody
        // else.
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("the trend names no backer, anywhere in the body")
    void theTrendNamesNoBacker() {
        Campaign campaign = liveCampaign();
        attribute(campaign.id(), "2026-03-10" + MIDDAY, "100.00", "SOCIAL", "twitter");
        rollUp("2026-03-10", "2026-03-10");

        String body = rest.exchange(
                        "/v1/projects/" + campaign.id() + "/analytics?from=2026-03-10&to=2026-03-10",
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(campaign.accessToken())),
                        String.class)
                .getBody();

        // The guarantee is that there is no field: referral_attributions has nowhere to
        // put a backer, so the rollup derived from it cannot acquire one.
        assertThat(body).doesNotContain("backer");
    }

    // ------------------------------------------------------------------
    // What is refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a range running backwards is refused rather than quietly swapped")
    void aRangeRunningBackwardsIsRefused() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response =
                analytics(campaign.id(), campaign.accessToken(), "?from=2026-03-10&to=2026-03-09");

        // Swapping the ends would answer a question nobody asked, plausibly.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "ANALYTICS_RANGE_INVALID");
    }

    @Test
    @DisplayName("a range longer than a year is refused")
    void aRangeLongerThanAYearIsRefused() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response =
                analytics(campaign.id(), campaign.accessToken(), "?from=2020-01-01&to=2026-01-01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "ANALYTICS_RANGE_INVALID");
    }

    @Test
    @DisplayName("a day that is not a date is a bad request")
    void aDayThatIsNotADateIsABadRequest() {
        Campaign campaign = liveCampaign();

        ResponseEntity<Map<String, Object>> response =
                analytics(campaign.id(), campaign.accessToken(), "?from=last-tuesday");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
        EmailAddress email = EmailAddress.of("analytics-api" + SEQUENCE.incrementAndGet() + "@example.com");
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
        Account creator = account();
        String slug = "analytics-api-" + SEQUENCE.incrementAndGet();
        UUID projectId =
                Campaigns.seed(dataSource, creator.id(), slug).state("LIVE").insert();
        return new Campaign(projectId, creator.accessToken());
    }

    /** One attributed pledge, written where the rollup will find it. */
    private void attribute(UUID projectId, String pledgedAt, String amount, String channel, String source) {
        OffsetDateTime confirmed = Instant.parse(pledgedAt).atOffset(ZoneOffset.UTC);
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO referral_attributions (
                            id, pledge_id, project_id, touch_id, channel, source, campaign, referrer_code,
                            amount, currency, pledged_at, attributed_at, event_id)
                        VALUES (?, ?, ?, NULL, ?, ?, NULL, NULL, ?, 'AZN', ?, ?, ?)
                        """,
                        Identifiers.newIdentifier(),
                        UUID.randomUUID(),
                        projectId,
                        channel,
                        source,
                        new BigDecimal(amount),
                        confirmed,
                        confirmed,
                        UUID.randomUUID());
    }

    private void rollUp(String from, String to) {
        rollups.rollUp(LocalDate.parse(from), LocalDate.parse(to));
    }

    private ResponseEntity<Map<String, Object>> analytics(UUID projectId, String accessToken, String query) {
        return rest.exchange(
                "/v1/projects/" + projectId + "/analytics" + query,
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                OBJECT);
    }

    /**
     * One element of a JSON array, as the object it is.
     *
     * <p>The cast is unchecked because the body was deserialised into a
     * {@code Map<String, Object>}; confined to one method so that the suppression is one
     * line rather than one per assertion.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object element) {
        return (Map<String, Object>) element;
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
