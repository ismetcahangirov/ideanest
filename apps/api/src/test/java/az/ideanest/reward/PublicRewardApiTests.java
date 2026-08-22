package az.ideanest.reward;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.discovery.domain.DiscoveryStatus;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * The backer's reward list, over HTTP. §4.5's first call, and PL-01.
 *
 * <p>The tests that carry the design are
 * {@link #theStatesThisListAnswersForAreExactlyTheOnesDiscoveryLists()} — one
 * assertion over all sixteen states of §6.1, which is what stops a suspended campaign
 * from going on selling —
 * {@link #aSecretTierIsOmittedUntilItsOwnTokenIsPresented()}, which is PL-15, and
 * {@link #aTierIsOfferedOnlyInsideItsAvailabilityWindow()}, tested on both edges
 * against the adjustable clock rather than by waiting for one.
 *
 * <p>Stock is written with a {@link JdbcTemplate} for the same reason
 * {@code RewardApiTests} does it: no endpoint in the service writes
 * {@code claimed_quantity} or {@code reserved_quantity} — epic #50 and #51 do — and
 * the entity does not expose them. Writing the columns directly is what a pledge will
 * do, and it is the only way to test a sold-out tier before there are pledges.
 */
class PublicRewardApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AdjustableClock clock;

    @AfterEach
    void clearRewardsAndReleaseTheClock() {
        // The clock bean is shared with the whole suite, so a test that froze it and
        // did not put it back would make every later test run at a fixed instant.
        clock.reset();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM shipping_rules");
        jdbc.update("DELETE FROM reward_tier_items");
        jdbc.update("DELETE FROM reward_tiers");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A registered, signed-in account: its access token and its identifier. */
    private record Creator(String accessToken, UUID id) {
    }

    private Creator creator() {
        EmailAddress email = EmailAddress.of("public-rewards" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Creator((String) signedIn.getBody().get("accessToken"), id);
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

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    private ResponseEntity<Map<String, Object>> post(String path, String token, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), OBJECT);
    }

    private ResponseEntity<Map<String, Object>> put(String path, String token, Object body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, bearer(token)), OBJECT);
    }

    /**
     * The endpoint under test, called the way the public calls it: no credential.
     *
     * <p>Every read in this file goes through here, so a header accidentally added to
     * one test cannot make the rest of the suite pass as somebody who is signed in.
     */
    private ResponseEntity<Map<String, Object>> publicRewards(UUID projectId, String query) {
        return rest.exchange(
                "/v1/projects/" + projectId + "/rewards/public" + query,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                OBJECT);
    }

    private ResponseEntity<Map<String, Object>> publicRewards(UUID projectId) {
        return publicRewards(projectId, "");
    }

    private UUID project(Creator creator) {
        Map<String, Object> created =
                post("/v1/projects", creator.accessToken(), Map.of("title", "A campaign")).getBody();
        return UUID.fromString((String) created.get("id"));
    }

    private Map<String, Object> item(Creator creator, UUID projectId, String name, boolean digital) {
        return post(
                        "/v1/projects/" + projectId + "/items",
                        creator.accessToken(),
                        Map.of("name", name, "isDigital", digital))
                .getBody();
    }

    /** A tier from a body, so each test says only what it cares about. */
    private Map<String, Object> reward(Creator creator, UUID projectId, Map<String, Object> body) {
        return post("/v1/projects/" + projectId + "/rewards", creator.accessToken(), body)
                .getBody();
    }

    private static Map<String, Object> tierBody(String title, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("price", Map.of("amount", amount, "currency", "AZN"));
        return body;
    }

    private static UUID idOf(Map<String, Object> resource) {
        return UUID.fromString((String) resource.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> body, String field) {
        return (List<Map<String, Object>>) body.get(field);
    }

    /** What a confirmed pledge and a checkout in progress will write. See the class comment. */
    private void takePlaces(UUID rewardId, int claimed, int reserved) {
        new JdbcTemplate(dataSource)
                .update(
                        "UPDATE reward_tiers SET claimed_quantity = ?, reserved_quantity = ? WHERE id = ?",
                        claimed,
                        reserved,
                        rewardId);
    }

    private void launch(UUID projectId) {
        Campaigns.launch(dataSource, projectId);
    }

    private void moveTo(UUID projectId, String state) {
        new JdbcTemplate(dataSource).update("UPDATE projects SET state = ? WHERE id = ?", state, projectId);
    }

    // ------------------------------------------------------------------
    // What a backer is shown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a live campaign answers with its tiers, their contents, and their shipping rates")
    void aLiveCampaignAnswersWithEverythingABackerNeedsToChoose() {
        Creator creator = creator();
        UUID projectId = project(creator);
        UUID mug = idOf(item(creator, projectId, "Enamel mug", false));
        UUID pdf = idOf(item(creator, projectId, "The soundtrack", true));

        Map<String, Object> body = tierBody("Super Early Bird", "45.00");
        body.put("description", "The first hundred.");
        body.put("estimatedDelivery", "2026-11-01");
        body.put("shippingType", "INTERNATIONAL");
        body.put("limitQuantity", 100);
        body.put("isEarlyBird", true);
        body.put(
                "items",
                List.of(
                        Map.of("itemId", mug.toString(), "quantity", 2),
                        Map.of("itemId", pdf.toString(), "quantity", 1)));
        UUID rewardId = idOf(reward(creator, projectId, body));

        put(
                "/v1/rewards/" + rewardId + "/shipping-rules",
                creator.accessToken(),
                Map.of("rules", List.of(Map.of("countryCode", "AZ", "amount", "5", "additionalItemAmount", "2"))));

        launch(projectId);

        ResponseEntity<Map<String, Object>> response = publicRewards(projectId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The campaign's currency is stated once, for the whole body.
        assertThat(response.getBody()).containsEntry("currency", "AZN");

        List<Map<String, Object>> rewards = listOf(response.getBody(), "rewards");
        assertThat(rewards).hasSize(1);
        Map<String, Object> tier = rewards.get(0);

        assertThat(tier)
                .containsEntry("id", rewardId.toString())
                .containsEntry("title", "Super Early Bird")
                .containsEntry("description", "The first hundred.")
                // §10.3: money is an object whose amount is a string, padded to the
                // scale of the column.
                .containsEntry("price", Map.of("amount", "45.00", "currency", "AZN"))
                .containsEntry("estimatedDelivery", "2026-11-01")
                .containsEntry("shippingType", "INTERNATIONAL")
                .containsEntry("limitQuantity", 100)
                .containsEntry("remainingQuantity", 100)
                .containsEntry("isEarlyBird", true)
                .containsEntry("isFeatured", false);

        // The contents are described rather than referenced: a backer holds no item
        // catalogue to look an identifier up in.
        assertThat(listOf(tier, "items"))
                .containsExactlyInAnyOrder(
                        Map.of("name", "Enamel mug", "quantity", 2, "isDigital", false),
                        Map.of("name", "The soundtrack", "quantity", 1, "isDigital", true));

        // PL-05: the destination drives the charge, so the client cannot quote a
        // total without these.
        assertThat(listOf(tier, "shippingRates"))
                // `perKilogramAmount` arrived with #77 and is zero for a tier priced flat.
                .containsExactly(Map.of(
                        "countryCode", "AZ",
                        "amount", "5.00",
                        "additionalItemAmount", "2.00",
                        "perKilogramAmount", "0.00"));
    }

    @Test
    @DisplayName("the secret token and the reservation counts never reach the public list")
    void thePublicListCarriesNothingThatBelongsToTheCreator() {
        Creator creator = creator();
        UUID projectId = project(creator);
        Map<String, Object> body = tierBody("A tier", "19.99");
        body.put("limitQuantity", 10);
        UUID rewardId = idOf(reward(creator, projectId, body));
        takePlaces(rewardId, 3, 2);
        launch(projectId);

        Map<String, Object> tier =
                listOf(publicRewards(projectId).getBody(), "rewards").get(0);

        // How many people are in this campaign's checkout right now is a commercial
        // fact about the creator; what is left is all a backer needs.
        assertThat(tier)
                .doesNotContainKey("secretToken")
                .doesNotContainKey("claimedQuantity")
                .doesNotContainKey("reservedQuantity")
                .doesNotContainKey("isSecret")
                .doesNotContainKey("version")
                .containsEntry("remainingQuantity", 5);
    }

    @Test
    @DisplayName("add-ons are a separate array from the tiers a backer selects")
    void addOnsAreSeparatedFromSelectableTiers() {
        Creator creator = creator();
        UUID projectId = project(creator);
        reward(creator, projectId, tierBody("The book", "45.00"));

        Map<String, Object> extra = tierBody("A second mug", "12.00");
        extra.put("isAddon", true);
        reward(creator, projectId, extra);
        launch(projectId);

        Map<String, Object> response = publicRewards(projectId).getBody();
        assertThat(listOf(response, "rewards"))
                .extracting(tier -> tier.get("title"))
                .containsExactly("The book");
        assertThat(listOf(response, "addons"))
                .extracting(tier -> tier.get("title"))
                .containsExactly("A second mug");
    }

    @Test
    @DisplayName("remaining stock is what is left, and null when the tier is unlimited")
    void remainingStockIsWhatIsLeftAndNullWhenUnlimited() {
        Creator creator = creator();
        UUID projectId = project(creator);

        Map<String, Object> limited = tierBody("Limited", "45.00");
        limited.put("limitQuantity", 10);
        UUID limitedId = idOf(reward(creator, projectId, limited));
        reward(creator, projectId, tierBody("Unlimited", "20.00"));

        // Three sold and two in somebody's checkout. A reservation is as taken as a
        // claim — it is somebody entering their card details.
        takePlaces(limitedId, 3, 2);
        launch(projectId);

        List<Map<String, Object>> rewards = listOf(publicRewards(projectId).getBody(), "rewards");
        assertThat(rewards.get(0)).containsEntry("limitQuantity", 10).containsEntry("remainingQuantity", 5);

        // Null rather than an absent key: a client that could not tell "unlimited"
        // from "the server did not say" would have to guess on the one field that
        // decides whether a backer is shown "3 left".
        assertThat(rewards.get(1)).containsEntry("limitQuantity", null).containsEntry("remainingQuantity", null);

        // A tier with nothing left is still listed. Hiding it would make the page
        // silently shorter every time one filled; refusing the pledge is what
        // POST /v1/pledges/draft is for.
        takePlaces(limitedId, 10, 0);
        assertThat(listOf(publicRewards(projectId).getBody(), "rewards").get(0))
                .containsEntry("remainingQuantity", 0);
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a draft campaign is not found, and says so exactly as an invented identifier does")
    void aDraftCampaignIsNotFound() {
        Creator creator = creator();
        UUID projectId = project(creator);
        reward(creator, projectId, tierBody("A tier nobody may see yet", "45.00"));

        ResponseEntity<Map<String, Object>> draft = publicRewards(projectId);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(draft.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");

        // The same answer an identifier that never existed gets, so the endpoint
        // cannot be used to find out what other people are preparing. Everything but
        // `instance`, which is §10.4's echo of the request path and is the one field
        // that must differ between two requests to two URLs.
        ResponseEntity<Map<String, Object>> invented = publicRewards(UUID.randomUUID());
        assertThat(invented.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutInstance(invented.getBody())).isEqualTo(withoutInstance(draft.getBody()));
    }

    private static Map<String, Object> withoutInstance(Map<String, Object> problem) {
        Map<String, Object> copy = new LinkedHashMap<>(problem);
        copy.remove("instance");
        return copy;
    }

    @Test
    @DisplayName("a suspended campaign stops offering its rewards")
    void aSuspendedCampaignIsNotFound() {
        Creator creator = creator();
        UUID projectId = project(creator);
        reward(creator, projectId, tierBody("A tier", "45.00"));
        launch(projectId);
        assertThat(publicRewards(projectId).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The one state the public has seen and may still not read. Trust and safety
        // stopped it, frequently with an investigation open, and going on selling
        // what has just been withdrawn is the worst failure this endpoint can have.
        moveTo(projectId, "SUSPENDED");
        assertThat(publicRewards(projectId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the states this list answers for are exactly the ones discovery lists")
    void theStatesThisListAnswersForAreExactlyTheOnesDiscoveryLists() {
        UUID creatorId = Campaigns.creator(dataSource, "public-reward-states");

        // All sixteen of §6.1, one campaign each, rather than the nine this endpoint
        // is expected to answer for: a rule that is only tested on the cases it
        // permits is a rule with no lower bound.
        Set<String> answered = new LinkedHashSet<>();
        for (ProjectState state : ProjectState.values()) {
            UUID projectId = Campaigns.seed(
                            dataSource, creatorId, "rewards-" + state.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .state(state.name())
                    .insert();
            if (publicRewards(projectId).getStatusCode() == HttpStatus.OK) {
                answered.add(state.name());
            }
        }

        // Two independent statements of one rule, compared. PublicProjects writes
        // the set down in the project module because it has ProjectState to say it
        // with; DiscoveryStatus writes it down in discovery because that module reads
        // the column as text. Neither may import the other, so this is where they are
        // held to each other -- and a campaign that can be found by browsing but
        // whose rewards cannot be read, or the reverse, is a platform answering one
        // question two ways.
        assertThat(answered).isEqualTo(DiscoveryStatus.PUBLIC_STATES);
    }

    // ------------------------------------------------------------------
    // Secret tiers, PL-15
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a secret tier is omitted until its own token is presented")
    void aSecretTierIsOmittedUntilItsOwnTokenIsPresented() {
        Creator creator = creator();
        UUID projectId = project(creator);
        reward(creator, projectId, tierBody("For everybody", "45.00"));

        Map<String, Object> hidden = tierBody("For the mailing list", "30.00");
        hidden.put("isSecret", true);
        Map<String, Object> created = reward(creator, projectId, hidden);
        String token = (String) created.get("secretToken");
        assertThat(token).isNotBlank();
        launch(projectId);

        // What makes a tier secret is that the public list leaves it out.
        assertThat(listOf(publicRewards(projectId).getBody(), "rewards"))
                .extracting(tier -> tier.get("title"))
                .containsExactly("For everybody");

        // A token that is not this tier's is no token at all.
        assertThat(listOf(publicRewards(projectId, "?token=" + token + "-not-quite").getBody(), "rewards"))
                .hasSize(1);

        // The link the creator sent out is the link that works.
        assertThat(listOf(publicRewards(projectId, "?token=" + token).getBody(), "rewards"))
                .extracting(tier -> tier.get("title"))
                .containsExactly("For everybody", "For the mailing list");

        // And it stays a tier like any other once unlocked: the token is not echoed
        // back, so a response cannot become a second way to distribute it.
        assertThat(listOf(publicRewards(projectId, "?token=" + token).getBody(), "rewards").get(1))
                .doesNotContainKey("secretToken");
    }

    // ------------------------------------------------------------------
    // The availability window
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tier is offered only inside its availability window, on both edges")
    void aTierIsOfferedOnlyInsideItsAvailabilityWindow() {
        Creator creator = creator();
        UUID projectId = project(creator);
        reward(creator, projectId, tierBody("Always", "45.00"));

        clock.freeze();
        // Truncated to milliseconds so the stored value is the value compared:
        // timestamptz holds microseconds, and a boundary test wants the instant it
        // asked for rather than one rounded underneath it.
        Instant opensAt = clock.instant().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
        Instant closesAt = opensAt.plus(Duration.ofMinutes(10));

        Map<String, Object> windowed = tierBody("Early bird", "20.00");
        windowed.put("availableFrom", opensAt.toString());
        windowed.put("availableUntil", closesAt.toString());
        reward(creator, projectId, windowed);
        launch(projectId);

        assertThat(titlesOf(projectId)).containsExactly("Always");

        // Exactly at the opening instant it is offered: the window is half-open,
        // [from, until), which is the only reading under which one tier can replace
        // another at midnight without both being offered for that instant.
        moveClockTo(opensAt);
        assertThat(titlesOf(projectId)).containsExactly("Always", "Early bird");

        // A moment before it closes, still offered.
        moveClockTo(closesAt.minusMillis(1));
        assertThat(titlesOf(projectId)).containsExactly("Always", "Early bird");

        // Exactly at the closing instant it is gone. §5.3 expresses withdrawing a
        // tier from sale as an available_until in the past — a tier with backers may
        // not be deleted — so this filter is what actually withdraws it.
        moveClockTo(closesAt);
        assertThat(titlesOf(projectId)).containsExactly("Always");
    }

    private void moveClockTo(Instant instant) {
        clock.advance(Duration.between(clock.instant(), instant));
        assertThat(clock.instant()).isEqualTo(instant);
    }

    private List<String> titlesOf(UUID projectId) {
        return listOf(publicRewards(projectId).getBody(), "rewards").stream()
                .map(tier -> (String) tier.get("title"))
                .toList();
    }

    // ------------------------------------------------------------------
    // Caching, §10.3
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the list revalidates rather than expiring, and its tag moves when stock does")
    void theListRevalidatesRatherThanExpiring() {
        Creator creator = creator();
        UUID projectId = project(creator);
        Map<String, Object> body = tierBody("Limited", "45.00");
        body.put("limitQuantity", 10);
        UUID rewardId = idOf(reward(creator, projectId, body));
        launch(projectId);

        ResponseEntity<Map<String, Object>> first = publicRewards(projectId);
        String cacheControl = first.getHeaders().getCacheControl();
        String etag = first.getHeaders().getETag();

        // "Keep this body, and ask before you use it again." Deliberately not a
        // max-age: a reward list showing places that have gone is the exact failure
        // PL-01's live stock check exists to prevent, and any shared freshness
        // window at all buys throughput by spending that guarantee.
        assertThat(cacheControl).contains("no-cache").contains("private").doesNotContain("max-age");
        assertThat(etag).isNotBlank();

        // The saving is the conditional request rather than the stale one.
        assertThat(revalidate(projectId, etag).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        // And the tag covers the stock, which is the field that moves most often. A
        // digest that skipped it would answer 304 for a list that is no longer true.
        takePlaces(rewardId, 1, 0);
        ResponseEntity<String> afterAPledge = revalidate(projectId, etag);
        assertThat(afterAPledge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterAPledge.getHeaders().getETag()).isNotEqualTo(etag);
        // The policy is on the 304 as well as on the 200, so a cache is never left
        // deciding for itself how long the stored body stays fresh.
        assertThat(revalidate(projectId, afterAPledge.getHeaders().getETag()).getHeaders().getCacheControl())
                .isEqualTo(cacheControl);
    }

    /** A conditional GET, as a client holding a previous answer makes it. */
    private ResponseEntity<String> revalidate(UUID projectId, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);
        return rest.exchange(
                "/v1/projects/" + projectId + "/rewards/public",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    // ------------------------------------------------------------------
    // The creator's list is untouched
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the creator's list still requires a token and still shows the secret tier")
    void theCreatorsListIsUnaffected() {
        Creator creator = creator();
        UUID projectId = project(creator);
        Map<String, Object> hidden = tierBody("For the mailing list", "30.00");
        hidden.put("isSecret", true);
        reward(creator, projectId, hidden);
        launch(projectId);

        // The two endpoints are two answers to two questions, and this is the one
        // that would break silently: a public path that shadowed the creator's would
        // leave the editor unable to withdraw a tier it can no longer see.
        ResponseEntity<String> anonymous = rest.exchange(
                "/v1/projects/" + projectId + "/rewards",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> asCreator = rest.exchange(
                "/v1/projects/" + projectId + "/rewards",
                HttpMethod.GET,
                new HttpEntity<>(bearer(creator.accessToken())),
                String.class);
        assertThat(asCreator.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asCreator.getBody()).contains("\"secretToken\":\"");
    }

    // ------------------------------------------------------------------
    // A campaign with nothing to offer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a campaign with no tiers answers with two empty arrays and its currency")
    void aCampaignWithNoTiersStillStatesItsCurrency() {
        Creator creator = creator();
        UUID projectId = project(creator);
        launch(projectId);

        Map<String, Object> body = publicRewards(projectId).getBody();
        // §5.3 allows a campaign with no reward tiers at all, and PL-02 makes
        // support without a reward a first-class pledge. A client showing that still
        // needs the currency to render an amount.
        assertThat(body).containsEntry("currency", "AZN");
        assertThat(listOf(body, "rewards")).isEmpty();
        assertThat(listOf(body, "addons")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the tiers come back in the creator's order")
    void tiersComeBackInTheCreatorsOrder() {
        Creator creator = creator();
        UUID projectId = project(creator);
        UUID first = idOf(reward(creator, projectId, tierBody("First", "10.00")));
        UUID second = idOf(reward(creator, projectId, tierBody("Second", "20.00")));

        // As a string, because the reorder endpoint answers with an array and this
        // file's helpers read objects.
        rest.exchange(
                "/v1/projects/" + projectId + "/rewards/reorder",
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of("rewardIds", List.of(second.toString(), first.toString())), bearer(creator.accessToken())),
                String.class);
        launch(projectId);

        // sort_order, which is a property of the list and the one thing a creator
        // arranges deliberately. The array is where it is expressed, so no client
        // has to re-sort.
        assertThat(titlesOf(projectId)).containsExactly("Second", "First");
    }
}
