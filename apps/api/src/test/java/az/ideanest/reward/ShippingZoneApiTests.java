package az.ideanest.reward;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.reward.application.ShippingRate;
import az.ideanest.reward.application.ShippingRates;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.infrastructure.UserRepository;
import java.util.HashMap;
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
 * §4.8's PM-11 to PM-13 (#77): shipping regions, and what they cost.
 *
 * <p>The tests that carry the design:
 *
 * <ul>
 *   <li>{@link #aNamedCountryAlwaysBeatsTheZoneItFallsIn()} — the precedence rule, and
 *       the reason it is a rule rather than a tie-break.
 *   <li>{@link #aDestinationCannotBelongToTwoRegions()} — what keeps precedence a
 *       two-way question, refused with a sentence rather than a constraint name.
 *   <li>{@link #anUnchangedRegionKeepsItsIdentifierAndItsRates()} — the reason zones
 *       are matched by folded name: recreating them would discard every rate.
 *   <li>{@link #aZoneRateWithAWeightComponentQuotesByWeight()} — PM-12 end to end,
 *       through the resolver the checkout uses.
 * </ul>
 */
class ShippingZoneApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private ShippingRates rates;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM shipping_zone_rules");
        jdbc.update("DELETE FROM shipping_zone_countries");
        jdbc.update("DELETE FROM shipping_zones");
        jdbc.update("DELETE FROM shipping_rules");
        jdbc.update("DELETE FROM reward_tier_items");
        jdbc.update("DELETE FROM reward_tiers");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
    }

    // ------------------------------------------------------------------
    // The regions themselves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a campaign names its regions and reads them back with their destinations")
    void aCampaignNamesItsRegions() {
        Account creator = account("zone-creator");
        UUID project = draftCampaign(creator);

        ResponseEntity<Map<String, Object>> saved =
                putZones(project, creator, List.of(zone("EU", List.of("de", "fr", "IT"))));

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> zones = zones(saved.getBody());
        assertThat(zones).singleElement().satisfies(zone -> {
            assertThat(zone).containsEntry("name", "EU");
            assertThat(zone.get("countryCodes"))
                    .as("a creator typing 'de' means Germany")
                    .isEqualTo(List.of("DE", "FR", "IT"));
        });
    }

    @Test
    @DisplayName("a region left out of the body is removed, along with the rates that priced it")
    void regionsAreReplacedWholesale() {
        Account creator = account("wholesale-creator");
        UUID project = draftCampaign(creator);
        putZones(project, creator, List.of(zone("EU", List.of("DE")), zone("Rest", List.of("US"))));

        ResponseEntity<Map<String, Object>> saved = putZones(project, creator, List.of(zone("EU", List.of("DE"))));

        assertThat(zones(saved.getBody())).singleElement().satisfies(zone ->
                assertThat(zone).containsEntry("name", "EU"));
    }

    /**
     * The reason zones are matched on their folded name rather than recreated.
     *
     * <p>A tier's rates name the zone by identifier, so deleting and recreating "EU" on
     * every edit would silently discard every rate every tier charges to it.
     */
    @Test
    @DisplayName("an unchanged region keeps its identifier, and therefore the rates that name it")
    void anUnchangedRegionKeepsItsIdentifierAndItsRates() {
        Account creator = account("identity-creator");
        UUID project = draftCampaign(creator);
        UUID zoneId = zoneId(putZones(project, creator, List.of(zone("EU", List.of("DE")))));
        UUID tier = rewardTier(project, creator, "Boxed set", "INTERNATIONAL");
        putRates(tier, creator, List.of(), List.of(zoneRate(zoneId, "12.00", "0.00", "0.00")));

        // The same region, spelled differently and with a country added.
        UUID afterEdit = zoneId(putZones(project, creator, List.of(zone("  eu ", List.of("DE", "FR")))));

        assertThat(afterEdit).isEqualTo(zoneId);
        assertThat(rates.ratesTo(project, List.of(tier), "FR"))
                .as("the rate survived the edit and now covers France too")
                .hasEntrySatisfying(tier, rate -> assertThat(rate.amount()).isEqualByComparingTo("12.00"));
    }

    @Test
    @DisplayName("a destination cannot belong to two regions")
    void aDestinationCannotBelongToTwoRegions() {
        Account creator = account("overlap-creator");
        UUID project = draftCampaign(creator);

        ResponseEntity<Map<String, Object>> refused = putZones(
                project, creator, List.of(zone("EU", List.of("DE")), zone("Neighbours", List.of("DE", "TR"))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "SHIPPING_ZONE_INVALID");
        assertThat((String) refused.getBody().get("detail"))
                .as("the creator reads which destination, not a constraint name")
                .contains("DE");
    }

    @Test
    @DisplayName("a region with no destinations is refused")
    void aRegionCoversSomething() {
        Account creator = account("empty-zone-creator");
        UUID project = draftCampaign(creator);

        assertThat(putZones(project, creator, List.of(zone("EU", List.of()))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("two regions folding to one name are refused")
    void twoRegionsCannotShareAName() {
        Account creator = account("dupe-zone-creator");
        UUID project = draftCampaign(creator);

        assertThat(putZones(project, creator, List.of(zone("EU", List.of("DE")), zone(" eu ", List.of("FR"))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a stranger cannot read or change a campaign's regions")
    void aStrangerCannotReachTheRegions() {
        Account creator = account("zoneguard-creator");
        UUID project = draftCampaign(creator);
        Account stranger = account("zoneguard-stranger");

        assertThat(exchange("/v1/projects/" + project + "/shipping-zones", HttpMethod.GET, stranger.accessToken(), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // Precedence and quoting — PM-11 to PM-13
    // ------------------------------------------------------------------

    /**
     * The precedence rule.
     *
     * <p>A creator who prices the EU at 12 and then writes a row for Germany at 8 has
     * said something specific about Germany. "Cheapest wins" would let them lose money
     * on every German parcel by adding a region; "last written wins" would make the
     * amount depend on the order they typed things months ago.
     */
    @Test
    @DisplayName("a named country always beats the region it falls in")
    void aNamedCountryAlwaysBeatsTheZoneItFallsIn() {
        Account creator = account("precedence-creator");
        UUID project = draftCampaign(creator);
        UUID zoneId = zoneId(putZones(project, creator, List.of(zone("EU", List.of("DE", "FR")))));
        UUID tier = rewardTier(project, creator, "Boxed set", "INTERNATIONAL");

        putRates(
                tier,
                creator,
                List.of(countryRate("DE", "8.00", "0.00", "0.00")),
                List.of(zoneRate(zoneId, "12.00", "0.00", "0.00")));

        assertThat(rates.ratesTo(project, List.of(tier), "DE"))
                .hasEntrySatisfying(tier, rate -> assertThat(rate.amount()).isEqualByComparingTo("8.00"));
        assertThat(rates.ratesTo(project, List.of(tier), "FR"))
                .as("a destination in the region and not named falls back to the region")
                .hasEntrySatisfying(tier, rate -> assertThat(rate.amount()).isEqualByComparingTo("12.00"));
    }

    @Test
    @DisplayName("a destination nobody priced is absent rather than free")
    void anUnpricedDestinationIsAbsent() {
        Account creator = account("unpriced-creator");
        UUID project = draftCampaign(creator);
        UUID zoneId = zoneId(putZones(project, creator, List.of(zone("EU", List.of("DE")))));
        UUID tier = rewardTier(project, creator, "Boxed set", "INTERNATIONAL");
        putRates(tier, creator, List.of(), List.of(zoneRate(zoneId, "12.00", "0.00", "0.00")));

        // A zero here would make the creator pay the carrier out of their own funding,
        // and nobody would notice until the parcels went out.
        assertThat(rates.ratesTo(project, List.of(tier), "US")).isEmpty();
    }

    /**
     * PM-12 through the resolver the checkout actually uses.
     *
     * <p>The arithmetic itself is pinned in {@code ShippingRateTests}; what this asserts
     * is that a per-kilogram rate survives the round trip through the API and the
     * database with its scale intact.
     */
    @Test
    @DisplayName("a region's rate carries its per-kilogram component through to a quote")
    void aZoneRateWithAWeightComponentQuotesByWeight() {
        Account creator = account("weight-creator");
        UUID project = draftCampaign(creator);
        UUID zoneId = zoneId(putZones(project, creator, List.of(zone("EU", List.of("DE")))));
        UUID tier = rewardTier(project, creator, "Boxed set", "INTERNATIONAL");
        putRates(tier, creator, List.of(), List.of(zoneRate(zoneId, "5.00", "1.00", "4.00")));

        ShippingRate rate = rates.ratesTo(project, List.of(tier), "DE").get(tier);

        assertThat(rate).isNotNull();
        assertThat(rate.countryCode()).as("the rate is resolved for the destination, not for the region").isEqualTo("DE");
        assertThat(rate.perKilogramAmount()).isEqualByComparingTo("4.00");
        assertThat(rate.costFor(1, 1500, "AZN"))
                .as("5.00 handling plus 1.5kg at 4.00")
                .isEqualByComparingTo("11.00");
    }

    @Test
    @DisplayName("a per-country rate keeps its per-kilogram component too")
    void aCountryRateCanBePricedByWeight() {
        Account creator = account("countryweight-creator");
        UUID project = draftCampaign(creator);
        UUID tier = rewardTier(project, creator, "Boxed set", "INTERNATIONAL");
        putRates(tier, creator, List.of(countryRate("DE", "5.00", "0.00", "2.50")), List.of());

        ShippingRate rate = rates.ratesTo(project, List.of(tier), "DE").get(tier);
        assertThat(rate.perKilogramAmount()).isEqualByComparingTo("2.50");
    }

    @Test
    @DisplayName("a rate naming a region that is not this campaign's is refused")
    void aRateCannotNameAnotherCampaignsRegion() {
        Account creator = account("foreign-zone-creator");
        UUID project = draftCampaign(creator);
        UUID other = draftCampaign(creator);
        UUID foreignZone = zoneId(putZones(other, creator, List.of(zone("EU", List.of("DE")))));
        UUID tier = rewardTier(project, creator, "Boxed set", "INTERNATIONAL");

        ResponseEntity<Map<String, Object>> refused =
                putRates(tier, creator, List.of(), List.of(zoneRate(foreignZone, "12.00", "0.00", "0.00")));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(String accessToken, UUID id) {
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

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account((String) signedIn.getBody().get("accessToken"), id);
    }

    private UUID draftCampaign(Account creator) {
        ResponseEntity<Map<String, Object>> created = exchange(
                "/v1/projects",
                HttpMethod.POST,
                creator.accessToken(),
                Map.of("title", "A campaign that ships abroad " + SEQUENCE.incrementAndGet()));
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private UUID rewardTier(UUID project, Account creator, String title, String shippingType) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("price", Map.of("amount", "40.00", "currency", "AZN"));
        body.put("shippingType", shippingType);
        ResponseEntity<Map<String, Object>> created =
                exchange("/v1/projects/" + project + "/rewards", HttpMethod.POST, creator.accessToken(), body);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private static Map<String, Object> zone(String name, List<String> countries) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("countryCodes", countries);
        return body;
    }

    private static Map<String, Object> countryRate(
            String country, String amount, String additional, String perKilogram) {

        Map<String, Object> body = new HashMap<>();
        body.put("countryCode", country);
        body.put("amount", amount);
        body.put("additionalItemAmount", additional);
        body.put("perKilogramAmount", perKilogram);
        return body;
    }

    private static Map<String, Object> zoneRate(UUID zoneId, String amount, String additional, String perKilogram) {
        Map<String, Object> body = new HashMap<>();
        body.put("zoneId", zoneId.toString());
        body.put("amount", amount);
        body.put("additionalItemAmount", additional);
        body.put("perKilogramAmount", perKilogram);
        return body;
    }

    private ResponseEntity<Map<String, Object>> putZones(UUID project, Account caller, List<Map<String, Object>> zones) {
        return exchange(
                "/v1/projects/" + project + "/shipping-zones",
                HttpMethod.PUT,
                caller.accessToken(),
                Map.of("zones", zones));
    }

    private ResponseEntity<Map<String, Object>> putRates(
            UUID tier, Account caller, List<Map<String, Object>> rules, List<Map<String, Object>> zoneRates) {

        Map<String, Object> body = new HashMap<>();
        body.put("rules", rules);
        body.put("zoneRates", zoneRates);
        return exchange("/v1/rewards/" + tier + "/shipping-rules", HttpMethod.PUT, caller.accessToken(), body);
    }

    private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, String token, Object body) {
        return rest.exchange(
                path,
                method,
                new HttpEntity<>(body, token == null ? jsonHeaders() : bearer(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> zones(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("zones");
    }

    private static UUID zoneId(ResponseEntity<Map<String, Object>> response) {
        return UUID.fromString((String) zones(response.getBody()).get(0).get("id"));
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
