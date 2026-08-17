package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The public backer list, over HTTP. §4.5's PL-12.
 *
 * <p><strong>Three of these carry the issue.</strong>
 *
 * <ul>
 *   <li>{@link #anAnonymousBackerIsCountedButNeverNamed()} is the capability itself,
 *       and it asserts both halves in one body: the name is gone and the count is not.
 *       A test that only checked the first would pass against an implementation that
 *       dropped anonymous backers altogether, which is the tempting wrong answer.
 *   <li>{@link #theLedgerKeepsTheBackerOnAnAnonymousPledge()} is the other half of the
 *       issue's sentence. §7.2 and §17.4 both require "pledge #123 was made by user X"
 *       to stay true, so it is asserted against the column rather than described in a
 *       comment.
 *   <li>{@link #ananonymisedAccountLosesItsNameAndKeepsItsPlaceInTheCount()} is the
 *       case nobody designed for. §17.4 severs the identity and keeps the financial
 *       row; the projection has no name to render and renders a backer with none,
 *       while the campaign's total is unchanged — closing an account does not retract
 *       the money it pledged.
 * </ul>
 *
 * <p>Every read here is made without a bearer token, because that is what a visitor
 * has. A suite that authenticated would be testing a different endpoint from the one
 * the security matcher opens.
 */
class PublicBackerApiTests extends AbstractIntegrationTest {

    /** Distinguishes the accounts these tests create; a counter, as elsewhere in this package. */
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

    @AfterEach
    void clearCheckouts() {
        // In dependency order rather than by cascade, because this is the cleanup and
        // not the assertion -- PledgeSchemaTests is where the cascades are checked.
        jdbc().update("DELETE FROM pledge_addons");
        jdbc().update("DELETE FROM pledges");
        jdbc().update("DELETE FROM idempotency_keys");
        jdbc().update("DELETE FROM shipping_rules");
        jdbc().update("DELETE FROM reward_tiers");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
    }

    // -----------------------------------------------------------------------
    // PL-12
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an anonymous backer is counted but never named")
    void anAnonymousBackerIsCountedButNeverNamed() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00");
        Campaigns.launch(dataSource, projectId);

        Account open = account("open");
        back(open, projectId, rewardId, "45.00", false);
        Account hidden = account("hidden");
        back(hidden, projectId, rewardId, "45.00", true);

        Map<String, Object> body = parse(publicBackers(projectId));

        // Anonymity hides who, never how many. A count that left the hidden backer out
        // would understate the campaign to everybody -- the visitor deciding whether to
        // join it, and the creator reading their own page.
        assertThat(body.get("backerCount")).isEqualTo(2);
        assertThat(backers(body)).hasSize(2);

        Map<String, Object> named = backerOf(body, false);
        assertThat(named.get("id")).isEqualTo(open.id().toString());
        assertThat(named.get("name")).isEqualTo("Test open");
        assertThat(named.get("slug")).isNotNull();
        assertThat(named.get("backedAt")).isNotNull();

        // Not "a null name beside a real identifier": there is no identifier either,
        // because the account identifier is the join key to the public profile and a
        // client holding it could resolve the name PL-12 exists to withhold.
        Map<String, Object> anonymous = backerOf(body, true);
        assertThat(anonymous.get("id")).isNull();
        assertThat(anonymous.get("name")).isNull();
        assertThat(anonymous.get("slug")).isNull();
        // The keys are present and null rather than absent, so a client does not have
        // to tell "not published" from "this server does not send that key".
        assertThat(anonymous).containsKeys("id", "name", "slug");
        // A timestamp names nobody, and it is what makes "recent backers" an order.
        assertThat(anonymous.get("backedAt")).isNotNull();

        // The whole body, checked as a set of values rather than field by field: the
        // hidden backer's name and slug must appear nowhere in it, including in a
        // field somebody adds later.
        assertThat(body.toString()).doesNotContain("Test hidden");
        assertThat(body.toString()).doesNotContain(hidden.id().toString());
    }

    @Test
    @DisplayName("the ledger keeps the backer on an anonymous pledge")
    void theLedgerKeepsTheBackerOnAnAnonymousPledge() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Account hidden = account("hidden");
        UUID pledgeId = back(hidden, projectId, null, "25.00", true);

        // §7.2 and §17.4: "pledge #123 was made by user X" has to stay true, because
        // every financial row referring to users.id is retained and the alternative
        // breaks the ledger. Anonymity is a rendering decision on the way out; it is
        // emphatically not a redaction of the row.
        assertThat(jdbc().queryForObject("SELECT backer_id FROM pledges WHERE id = ?", UUID.class, pledgeId))
                .isEqualTo(hidden.id());
        assertThat(jdbc().queryForObject("SELECT is_anonymous FROM pledges WHERE id = ?", Boolean.class, pledgeId))
                .isTrue();
    }

    @Test
    @DisplayName("an anonymised account loses its name and keeps its place in the count")
    void ananonymisedAccountLosesItsNameAndKeepsItsPlaceInTheCount() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        Account leaving = account("leaving");
        back(leaving, projectId, null, "25.00", false);

        Map<String, Object> before = parse(publicBackers(projectId));
        assertThat(backerOf(before, false).get("name")).isEqualTo("Test leaving");

        // What §17.4's anonymisation leaves behind: the pledge row intact, and an
        // account no finder in the user module will return. Written directly rather
        // than driven through the deletion endpoint and the job, because the grace
        // period is thirty days and this suite is about what the campaign page shows
        // afterwards. Every check constraint still applies, which is why the request
        // that the anonymisation follows is stamped too, thirty days before the date it
        // came due -- users_deletion_request_is_complete,
        // users_deletion_is_scheduled_after_request, users_anonymisation_follows_a_request
        // and users_anonymisation_implies_deletion together are what makes this a row
        // the application could have produced rather than one only a test can write.
        jdbc().update(
                        """
                        UPDATE users
                           SET deletion_requested_at = now() - interval '31 days',
                               deletion_scheduled_at = now() - interval '1 day',
                               anonymised_at = now(),
                               deleted_at = now()
                         WHERE id = ?
                        """,
                        leaving.id());

        Map<String, Object> after = parse(publicBackers(projectId));
        // Still a backer -- closing an account does not retract the money it pledged.
        assertThat(after.get("backerCount")).isEqualTo(1);
        // And rendered as what they now are: somebody whose identity the platform
        // deliberately no longer holds. The sealed projection has nowhere to put a name
        // it does not have, so this falls out rather than being handled.
        assertThat(backerOf(after, true).get("name")).isNull();
    }

    // -----------------------------------------------------------------------
    // What counts as a backing
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a draft that has not been confirmed is not yet a backing")
    void aDraftIsNotYetABacking() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00");
        Campaigns.launch(dataSource, projectId);

        // Drafted and deliberately not confirmed: a reservation with a five-minute
        // life (PL-13), not a commitment.
        draft(account("browsing"), projectId, rewardId, "45.00", false);

        Map<String, Object> body = parse(publicBackers(projectId));

        // Counting it would make the public number rise every time somebody opened a
        // checkout and fall again when they wandered off -- and would publish that a
        // named person is mid-checkout on a campaign they have not decided about.
        assertThat(body.get("backerCount")).isEqualTo(0);
        assertThat(backers(body)).isEmpty();
        assertThat(rewardTiers(body)).isEmpty();
    }

    @Test
    @DisplayName("the per-tier counts include anonymous backers and exclude support-only pledges")
    void perTierCountsAreAggregatesOverEverybody() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        UUID rewardId = reward(creator, projectId, "A boxed set", "45.00");
        Campaigns.launch(dataSource, projectId);

        back(account("open"), projectId, rewardId, "45.00", false);
        back(account("hidden"), projectId, rewardId, "45.00", true);
        // §4.5's PL-02: support with no reward. Counted on the campaign, and belonging
        // to no tier.
        back(account("supporter"), projectId, null, "10.00", false);

        Map<String, Object> body = parse(publicBackers(projectId));

        assertThat(body.get("backerCount")).isEqualTo(3);
        // §4.4's Rewards tab. Two on the tier, including the one who asked not to be
        // named -- an aggregate says "this tier is popular" without saying it about
        // anybody.
        assertThat(rewardTiers(body))
                .containsExactly(Map.of("rewardTierId", rewardId.toString(), "backerCount", 2));
    }

    // -----------------------------------------------------------------------
    // The endpoint
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the list is public, and a campaign that is not public is answered as one that does not exist")
    void theListIsPublicAndHidesTheCampaignsNobodyMayRead() {
        Account creator = account("creator");
        UUID projectId = project(creator);

        // Still a DRAFT campaign: it has a public page for nobody.
        ResponseEntity<String> hidden = publicBackers(projectId);
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // 404 and not 403. This endpoint takes no credential, so a 403 would be an
        // oracle any stranger could ask about what somebody is still preparing.
        assertThat(parse(hidden).get("code")).isEqualTo("PROJECT_NOT_FOUND");

        Campaigns.launch(dataSource, projectId);
        // No bearer token anywhere in this suite: a visitor deciding whether to
        // register is the audience the matcher opens this for.
        assertThat(publicBackers(projectId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the response carries an ETag and revalidates to 304")
    void theResponseRevalidates() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);
        back(account("open"), projectId, null, "25.00", false);

        ResponseEntity<String> first = publicBackers(projectId);
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotNull();
        // §10.3 asks for both on a public read. Nothing in this body belongs to a
        // person who did not publish it, which is what lets it be shared.
        assertThat(first.getHeaders().getCacheControl()).contains("public").contains("max-age=60");

        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(etag);
        ResponseEntity<String> revalidated = rest.exchange(
                "/v1/projects/" + projectId + "/backers/public",
                HttpMethod.GET,
                new HttpEntity<>(conditional),
                String.class);

        assertThat(revalidated.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        // The policy survives the revalidation. A 304 that dropped it would leave a
        // cache deciding for itself how long the stored body stays fresh.
        assertThat(revalidated.getHeaders().getCacheControl()).contains("max-age=60");
    }

    @Test
    @DisplayName("the limit bounds the page and never the count")
    void theLimitBoundsThePageAndNeverTheCount() {
        Account creator = account("creator");
        UUID projectId = project(creator);
        Campaigns.launch(dataSource, projectId);

        back(account("one"), projectId, null, "10.00", false);
        back(account("two"), projectId, null, "10.00", false);
        back(account("three"), projectId, null, "10.00", false);

        Map<String, Object> page = parse(publicBackers(projectId, "?limit=1"));

        // The failure this is here to refuse is a caller taking the length of the list
        // for the campaign's backer count. On a campaign small enough to fit in one
        // page the two agree, which is exactly why the mistake survives review.
        assertThat(backers(page)).hasSize(1);
        assertThat(page.get("backerCount")).isEqualTo(3);

        // A limit is a client's hint about how much it can draw, not an assertion
        // about the campaign: there is nothing to correct in an absurd one.
        assertThat(backers(parse(publicBackers(projectId, "?limit=100000")))).hasSize(3);
        assertThat(backers(parse(publicBackers(projectId, "?limit=-4")))).hasSize(3);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** A registered, signed-in account: its access token and its identifier. */
    private record Account(String accessToken, UUID id) {
    }

    private Account account(String role) {
        String marker = role + "-" + SEQUENCE.incrementAndGet();
        String email = marker + "@example.com";

        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "Test " + role),
                String.class);

        Map<String, Object> signedIn = parse(rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                String.class));

        UUID id = jdbc().queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID.class, email);
        return new Account((String) signedIn.get("accessToken"), id);
    }

    private UUID project(Account creator) {
        return id(parse(post("/v1/projects", creator, null, Map.of("title", "A campaign"))));
    }

    /** A reward tier, before the campaign launches — §5.3 freezes the price once it is live. */
    private UUID reward(Account creator, UUID projectId, String title, String price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("price", Map.of("amount", price, "currency", "AZN"));
        return id(parse(post("/v1/projects/" + projectId + "/rewards", creator, null, body)));
    }

    /**
     * A backer, all the way through §4.5: draft, then confirm.
     *
     * <p>Through HTTP rather than by writing the row, because the property under test
     * is that the flag a client sends to {@code POST /v1/pledges/draft} is the flag
     * this list reads — a fixture that wrote {@code is_anonymous} itself would prove
     * the projection works on data the application never produced.
     */
    private UUID back(Account backer, UUID projectId, UUID rewardTierId, String contribution, boolean anonymous) {
        UUID pledgeId = draft(backer, projectId, rewardTierId, contribution, anonymous);
        ResponseEntity<String> confirmed = post("/v1/pledges/" + pledgeId + "/confirm", backer, newKey(), Map.of());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return pledgeId;
    }

    private UUID draft(Account backer, UUID projectId, UUID rewardTierId, String contribution, boolean anonymous) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId.toString());
        if (rewardTierId != null) {
            body.put("rewardTierId", rewardTierId.toString());
        }
        body.put("contribution", Map.of("amount", contribution, "currency", "AZN"));
        body.put("isAnonymous", anonymous);

        ResponseEntity<String> created = post("/v1/pledges/draft", backer, newKey(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id(parse(created));
    }

    /** A fresh {@code Idempotency-Key}. §10.3 makes it a UUID. */
    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private ResponseEntity<String> publicBackers(UUID projectId) {
        return publicBackers(projectId, "");
    }

    /** Deliberately without a bearer token. This endpoint has no caller to establish. */
    private ResponseEntity<String> publicBackers(UUID projectId, String query) {
        return rest.getForEntity("/v1/projects/" + projectId + "/backers/public" + query, String.class);
    }

    private ResponseEntity<String> post(String path, Account account, String idempotencyKey, Object body) {
        HttpHeaders headers = bearer(account.accessToken());
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
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

    // -----------------------------------------------------------------------
    // Readings
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(ResponseEntity<String> response) {
        try {
            return json.readValue(response.getBody(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Not a JSON object: " + response.getBody(), e);
        }
    }

    private static UUID id(Map<String, Object> resource) {
        return UUID.fromString((String) resource.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> backers(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("backers");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rewardTiers(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("rewardTiers");
    }

    /** The one backer in this body that is, or is not, anonymous. */
    private static Map<String, Object> backerOf(Map<String, Object> body, boolean anonymous) {
        return backers(body).stream()
                .filter(backer -> Boolean.valueOf(anonymous).equals(backer.get("isAnonymous")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No backer with isAnonymous=" + anonymous + " in " + body));
    }
}
