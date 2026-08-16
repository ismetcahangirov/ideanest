package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §4.3's Location filter and its Near me sort (#47).
 *
 * <p><strong>The distance is checked against the world, not against itself.</strong> A
 * great-circle formula with a factor of {@code pi/180} missing agrees with itself
 * perfectly and is wrong by a factor of fifty-seven, so the first two tests here compare
 * V16's seeded coordinates against two published real distances — Baku to Ganja, and
 * Baku to Istanbul — with a tolerance wide enough to allow for the sphere
 * {@code earthdistance} measures on and narrow enough that no arithmetic error survives.
 *
 * <p>The rest is what §4.3 promises and what the module's own rules require of anything
 * added to it: that the filters compose with everything already there, that the facet
 * counts obey the exclude-own-dimension rule, that the cursor pins the origin the way
 * #42 pinned the clock and #44 pinned the weights, and that nothing here is a way to see
 * a campaign the public may not.
 */
class DiscoveryProximityTests extends DiscoveryTestSupport {

    /**
     * Baku, already quantised to the two places the API accepts.
     *
     * <p>Written in the coarse form on purpose: every test here sends what a client may
     * actually send, so none of them can pass because of a precision the API refuses.
     */
    private static final String BAKU = "40.41,49.87";

    /** Istanbul — an origin a long way outside the seeded gazetteer. */
    private static final String ISTANBUL = "41.01,28.98";

    /** Nearest first from {@link #BAKU}, with the campaign that has no location last. */
    private static final List<String> FROM_BAKU = List.of(
            "at-baki", "at-sumqayit", "at-samaxi", "at-lenkeran", "at-gence", "at-naxcivan", "at-istanbul", "nowhere");

    private JdbcTemplate jdbc;

    @BeforeEach
    void seedCampaignsAcrossTheCountry() {
        Campaigns.clear(dataSource);
        jdbc = new JdbcTemplate(dataSource);

        // The one place outside V16's Azerbaijani seed. It gives the country filter a
        // second value to be a filter between, and it is removed by Campaigns.clear.
        Campaigns.location(dataSource, "istanbul", "TR", "41.0082", "28.9784", "İstanbul");

        var creator = Campaigns.creator(dataSource, "proximity-creator");
        for (Map.Entry<String, String> at : Map.of(
                        "at-baki", "baki",
                        "at-sumqayit", "sumqayit",
                        "at-samaxi", "samaxi",
                        "at-lenkeran", "lenkeran",
                        "at-gence", "gence",
                        "at-naxcivan", "naxcivan",
                        "at-istanbul", "istanbul")
                .entrySet()) {
            Campaigns.seed(dataSource, creator, at.getKey())
                    // One word every campaign here shares, so that `/v1/search` has
                    // something to match and the near-me order over a searched feed is
                    // the same order over the same eight campaigns.
                    .title("proximity " + at.getKey())
                    .state("LIVE")
                    .category("games")
                    .location(at.getValue())
                    .insert();
        }
        // The state every campaign on the platform is actually in: no write path sets
        // location_id yet. It has to keep working under every other sort.
        Campaigns.seed(dataSource, creator, "nowhere")
                .title("proximity nowhere")
                .state("LIVE")
                .category("games")
                .insert();
    }

    // -----------------------------------------------------------------------
    // The distance, against the world
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Baku to Ganja measures about 300 km, which is what it is")
    void theDistanceAgreesWithARealDistanceInsideTheCountry() {
        BigDecimal kilometres = between("baki", "gence").movePointLeft(3);

        // Published great-circle distance, roughly 299 km. The tolerance allows for
        // earthdistance measuring on a sphere of radius 6378168 m rather than on the
        // WGS84 spheroid — about +0.2% at this latitude — and for the seeded centroids
        // being city centres rather than any particular point in them.
        assertThat(kilometres).isBetween(new BigDecimal("290"), new BigDecimal("310"));
    }

    @Test
    @DisplayName("Baku to Istanbul measures about 1760 km, across two countries")
    void theDistanceAgreesWithARealDistanceAcrossTheRegion() {
        BigDecimal kilometres = between("baki", "istanbul").movePointLeft(3);

        // Published air distance, roughly 1760 km. Deliberately an order of magnitude
        // above the first case: a formula that is right at 300 km and wrong at 1700 is
        // a formula that has confused a chord for an arc, and one test cannot see that.
        assertThat(kilometres).isBetween(new BigDecimal("1740"), new BigDecimal("1790"));
    }

    @Test
    @DisplayName("distance is measured through the earth's centre, so the antimeridian is not a wall")
    void theAntimeridianIsNotADiscontinuity() {
        // Two points 0.2 degrees apart at the equator, one either side of 180. A
        // formula that treats longitude as a number line puts them 40,000 km apart; the
        // right answer is 22 km, and ll_to_earth gets it because it converts to
        // three-dimensional cartesian coordinates before measuring anything.
        //
        // `ZZ` is the ISO 3166-1 user-assigned code, used here precisely because these
        // two points are in the middle of the Pacific and are not in a country.
        Campaigns.location(dataSource, "west-of-the-line", "ZZ", "0.0000", "179.9000", "West of the line");
        Campaigns.location(dataSource, "east-of-the-line", "ZZ", "0.0000", "-179.9000", "East of the line");

        assertThat(between("west-of-the-line", "east-of-the-line").movePointLeft(3))
                .isBetween(new BigDecimal("21"), new BigDecimal("24"));

        var creator = Campaigns.creator(dataSource, "proximity-creator");
        Campaigns.seed(dataSource, creator, "at-west").state("LIVE").location("west-of-the-line").insert();
        Campaigns.seed(dataSource, creator, "at-east").state("LIVE").location("east-of-the-line").insert();

        // And an origin sitting exactly on the antimeridian finds both, at 11 km each.
        assertThat(slugs(feed("?sort=near_me&near={near}&radiusKm=12&limit=100", "0,180")))
                .containsExactlyInAnyOrder("at-west", "at-east");
    }

    @Test
    @DisplayName("a pole is not a discontinuity either")
    void thePoleIsNotADiscontinuity() {
        // Two points on opposite meridians, both 0.1 degrees from the north pole. They
        // are 22 km apart over the top; a latitude/longitude bounding box would call
        // them half the world apart, because their longitudes differ by 180.
        Campaigns.location(dataSource, "near-the-pole-a", "ZZ", "89.9000", "0.0000", "Near the pole A");
        Campaigns.location(dataSource, "near-the-pole-b", "ZZ", "89.9000", "180.0000", "Near the pole B");

        assertThat(between("near-the-pole-a", "near-the-pole-b").movePointLeft(3))
                .isBetween(new BigDecimal("21"), new BigDecimal("24"));

        var creator = Campaigns.creator(dataSource, "proximity-creator");
        Campaigns.seed(dataSource, creator, "at-pole-a").state("LIVE").location("near-the-pole-a").insert();
        Campaigns.seed(dataSource, creator, "at-pole-b").state("LIVE").location("near-the-pole-b").insert();

        assertThat(slugs(feed("?sort=near_me&near={near}&radiusKm=12&limit=100", "90,0")))
                .containsExactlyInAnyOrder("at-pole-a", "at-pole-b");
    }

    // -----------------------------------------------------------------------
    // The order
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("near me orders by distance from the origin, nearest first")
    void nearMeOrdersByDistance() {
        assertThat(slugs(feed("?sort=near_me&near={near}&limit=100", BAKU))).isEqualTo(FROM_BAKU);
    }

    @Test
    @DisplayName("a different origin is a different order, not the same one relabelled")
    void theOrderFollowsTheOrigin() {
        List<String> fromIstanbul = slugs(feed("?sort=near_me&near={near}&limit=100", ISTANBUL));

        assertThat(fromIstanbul)
                .isEqualTo(List.of(
                        "at-istanbul",
                        "at-naxcivan",
                        "at-gence",
                        "at-samaxi",
                        "at-lenkeran",
                        "at-sumqayit",
                        "at-baki",
                        "nowhere"));
        // Not merely reversed: Shamakhi is nearer Istanbul than Lankaran is, and
        // further from Baku than Lankaran is not. An implementation that sorted by
        // distance from a fixed point and then reversed on request would pass the
        // previous test and fail this one.
        assertThat(fromIstanbul).isNotEqualTo(FROM_BAKU.reversed());
    }

    @Test
    @DisplayName("a third origin, inland, orders the same campaigns a third way")
    void aThirdOriginOrdersThemAgain() {
        // Ganja, in the west. Nakhchivan and Shamakhi are both closer to it than Baku
        // is, which is true under neither of the two orders above.
        assertThat(slugs(feed("?sort=near_me&near={near}&limit=100", "40.68,46.36")))
                .isEqualTo(List.of(
                        "at-gence",
                        "at-naxcivan",
                        "at-samaxi",
                        "at-sumqayit",
                        // Baku is nearer Ganja than Lankaran is — 299 km against 303 —
                        // which is true under neither of the two orders above, and is
                        // the kind of fact an implementation that ordered by anything
                        // other than distance would get wrong.
                        "at-baki",
                        "at-lenkeran",
                        "at-istanbul",
                        "nowhere"));
    }

    @Test
    @DisplayName("a campaign with no location sorts last under near me, and is not dropped")
    void campaignsWithNoLocationSortLast() {
        List<String> returned = slugs(feed("?sort=near_me&near={near}&limit=100", BAKU));

        // Last, and present. §4.3 lists Near me under sorts and proximity under the
        // Location filter: this is the sort, and a sort that removed most of the
        // platform from the feed would be a filter nobody asked for.
        assertThat(returned).endsWith("nowhere");
        assertThat(returned).hasSize(8);
    }

    @Test
    @DisplayName("a campaign with no location still appears under every other sort")
    void campaignsWithNoLocationSurviveEveryOtherSort() {
        for (String sort : List.of("newest", "ending_soon", "most_funded", "most_backed", "popularity", "relevance")) {
            assertThat(slugs(feed("?limit=100&sort=" + sort)))
                    .withFailMessage("sort=%s lost the campaign with no location", sort)
                    .contains("nowhere");
        }
    }

    @Test
    @DisplayName("a radius excludes the campaign with no location, because a sort is not a filter")
    void aRadiusExcludesWhatItCannotMeasure() {
        // The other half of the rule above. A campaign whose location is unknown cannot
        // be shown to be within fifty kilometres of anywhere, so a filter drops it
        // where the sort keeps it.
        assertThat(slugs(feed("?sort=near_me&near={near}&radiusKm=50&limit=100", BAKU)))
                .containsExactly("at-baki", "at-sumqayit");
    }

    // -----------------------------------------------------------------------
    // The radius, at the boundary
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a campaign exactly at the radius is inside it")
    void theRadiusBoundaryIsInclusive() {
        BigDecimal metres = fromBakuTo("samaxi");

        // Exactly the distance, in kilometres to the millimetre — which is expressible
        // only because LocationFilter keeps a radius to the same precision the distance
        // is rounded to. Testing the boundary rather than the middle is the whole point:
        // the middle passes under `<`, `<=`, and several wrong expressions besides.
        assertThat(slugs(feed(
                        "?sort=near_me&near={near}&radiusKm={radius}&limit=100",
                        BAKU,
                        metres.movePointLeft(3).toPlainString())))
                .contains("at-samaxi");
    }

    @Test
    @DisplayName("a campaign one millimetre beyond the radius is outside it")
    void theRadiusExcludesBeyondTheBoundary() {
        BigDecimal metres = fromBakuTo("samaxi");

        List<String> returned = slugs(feed(
                "?sort=near_me&near={near}&radiusKm={radius}&limit=100",
                BAKU,
                metres.subtract(new BigDecimal("0.001")).movePointLeft(3).toPlainString()));

        assertThat(returned).doesNotContain("at-samaxi");
        // And everything nearer is still there, so this is a boundary rather than an
        // off-by-everything.
        assertThat(returned).containsExactly("at-baki", "at-sumqayit");
    }

    @Test
    @DisplayName("a radius composes with a sort that is not near me")
    void aRadiusIsAFilterInItsOwnRight() {
        // §4.3 puts proximity under Location, which is a filter, and Near me under
        // sorts. They are two controls and either works without the other.
        assertThat(slugs(feed("?sort=newest&near={near}&radiusKm=250&limit=100", BAKU)))
                .containsExactlyInAnyOrder("at-baki", "at-sumqayit", "at-samaxi", "at-lenkeran");
    }

    @Test
    @DisplayName("a radius past the bound is refused rather than clamped")
    void anUnboundedRadiusIsRefused() {
        // Clamping a limit gives a client a working page; clamping a radius would
        // silently narrow somebody's search and return fewer campaigns than they asked
        // to see, which is a change of meaning in the direction that hides results.
        ResponseEntity<Map<String, Object>> response =
                get("/v1/discover?near={near}&radiusKm=20000", new HttpHeaders(), BAKU);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "DISCOVERY_VALUE_UNKNOWN");

        assertThat(get("/v1/discover?near={near}&radiusKm=0", new HttpHeaders(), BAKU)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // Country and city
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("country narrows the feed and is no longer refused")
    void countryFiltersTheFeed() {
        assertThat(slugs(feed("?country=TR&limit=100"))).containsExactly("at-istanbul");
        assertThat(slugs(feed("?country=AZ&limit=100")))
                .containsExactlyInAnyOrder(
                        "at-baki", "at-sumqayit", "at-samaxi", "at-lenkeran", "at-gence", "at-naxcivan");
        // Several values within one dimension are OR'd: a campaign is in one country.
        assertThat(slugs(feed("?country=AZ,TR&limit=100"))).hasSize(7);
        // And a country nobody is in is an empty feed rather than a 400 — the rule for
        // an open vocabulary, the same one `category` follows.
        assertThat(items(feed("?country=FR&limit=100"))).isEmpty();
    }

    @Test
    @DisplayName("city matches the folded slug, so Bakı and BAKI are one city")
    void cityFiltersOnTheFoldedForm() {
        // The whole reason locations are reference data rather than free text: every
        // way of writing the place's own name lands on one row, because the binder
        // folds what arrives with §11.3's fold and V16 stores the same fold.
        for (String spelling : List.of("baki", "Bakı", "BAKI", "bakı")) {
            assertThat(slugs(feed("?city={city}&limit=100", spelling)))
                    .withFailMessage("city=%s did not find the campaign in Baku", spelling)
                    .containsExactly("at-baki");
        }
        assertThat(slugs(feed("?city=baki,gence&limit=100"))).containsExactlyInAnyOrder("at-baki", "at-gence");

        // And the English exonym is deliberately NOT a value this filter takes. `city`
        // is a handle from an open vocabulary, exactly like `category` and `tag` and
        // `programme`, and the facet panel is where a client gets it — nobody expects
        // `?category=Oyunlar` to work either. Matching exonyms here would be a second
        // answer to "what is this place called", competing with the one
        // `location_translations` already gives, and would mean a filter whose accepted
        // values changed every time somebody added a translation.
        assertThat(items(feed("?city=Baku&limit=100"))).isEmpty();
    }

    @Test
    @DisplayName("country and city compose with each other and with every other filter")
    void locationComposesWithEverythingElse() {
        // Country AND city: a narrowing, like every other pair of dimensions.
        assertThat(items(feed("?country=TR&city=baki&limit=100"))).isEmpty();
        assertThat(slugs(feed("?country=AZ&city=baki&limit=100"))).containsExactly("at-baki");

        // With status, category, a sort, and a radius at once.
        assertThat(slugs(feed(
                        "?country=AZ&status=live&category=games&sort=near_me&near={near}&radiusKm=350&limit=100",
                        BAKU)))
                .containsExactly("at-baki", "at-sumqayit", "at-samaxi", "at-lenkeran", "at-gence");

        // And with free text, on /v1/search as well as /v1/discover.
        assertThat(slugs(search("?q={q}&country=TR&limit=100", "proximity"))).containsExactly("at-istanbul");
        assertThat(items(search("?q={q}&country=FR&limit=100", "proximity"))).isEmpty();
    }

    @Test
    @DisplayName("near me is served on /v1/search too, and pages there")
    void nearMeReachesTheSearchEndpoint() {
        // A capability declared once has to reach both endpoints, or a client would
        // find that its search results could not be ordered by distance.
        assertThat(slugs(search("?q={q}&sort=near_me&near={near}&limit=100", "proximity", BAKU)))
                .isEqualTo(FROM_BAKU);
    }

    // -----------------------------------------------------------------------
    // Facets
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the panel counts every country and every city, including the empty ones")
    void locationFacetsAreCounted() {
        Map<String, Object> facets = facets("");

        assertThat(count(facets, "countries", "AZ")).isEqualTo(6);
        assertThat(count(facets, "countries", "TR")).isEqualTo(1);
        assertThat(count(facets, "cities", "baki")).isEqualTo(1);
        assertThat(count(facets, "cities", "gence")).isEqualTo(1);
        // A fixed vocabulary: a city with nothing in it is a zero rather than a missing
        // row, because a control that vanishes when its count reaches zero is a control
        // that moves under the reader's cursor.
        assertThat(count(facets, "cities", "quba")).isZero();
        // And the name is resolved, not the slug echoed back.
        assertThat(nameOf(facets, "cities", "baki")).isEqualTo("Bakı");
    }

    @Test
    @DisplayName("city names follow Accept-Language, and fall back to the endonym")
    void cityNamesAreLocalised() {
        HttpHeaders english = new HttpHeaders();
        english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
        Map<String, Object> facets = get("/v1/discover/facets", english).getBody();

        assertThat(nameOf(facets, "cities", "baki")).isEqualTo("Baku");

        HttpHeaders russian = new HttpHeaders();
        russian.set(HttpHeaders.ACCEPT_LANGUAGE, "ru");
        // V16 seeds no Russian names on purpose — a transliteration of eighteen place
        // names is not something a migration should invent — so the fallback is the
        // endonym rather than English, which is the right default for a proper noun.
        assertThat(nameOf(get("/v1/discover/facets", russian).getBody(), "cities", "baki"))
                .isEqualTo("Bakı");
    }

    @Test
    @DisplayName("the location facets exclude their own dimension, country and city and radius alike")
    void locationFacetsExcludeTheirOwnDimension() {
        // §4.3 gives Location one row naming all three controls, so they are one
        // dimension. A caller who picked Turkey and was shown a zero beside Azerbaijan
        // would be looking at a dead end: the only move the panel offers is to clear
        // the filter.
        Map<String, Object> byCountry = facets("?country=TR");
        assertThat(count(byCountry, "countries", "AZ")).isEqualTo(6);
        assertThat(count(byCountry, "cities", "baki")).isEqualTo(1);

        Map<String, Object> byCity = facets("?city=baki");
        assertThat(count(byCity, "cities", "gence")).isEqualTo(1);
        assertThat(count(byCity, "countries", "TR")).isEqualTo(1);

        Map<String, Object> byRadius = facets("?near={near}&radiusKm=50", BAKU);
        assertThat(count(byRadius, "cities", "gence")).isEqualTo(1);
        assertThat(count(byRadius, "countries", "TR")).isEqualTo(1);
    }

    @Test
    @DisplayName("every other facet does apply the location filter")
    void otherFacetsRespectTheLocationFilter() {
        // The other half of the rule: excluding a dimension from its own counts is not
        // the same as ignoring it everywhere. Under country=TR the status and category
        // counts describe the one campaign in Turkey.
        Map<String, Object> facets = facets("?country=TR");

        assertThat(count(facets, "status", "live")).isEqualTo(1);
        assertThat(count(facets, "categories", "games")).isEqualTo(1);

        assertThat(count(facets("?near={near}&radiusKm=50", BAKU), "status", "live"))
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // The cursor
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a full walk under near me returns every campaign exactly once")
    void theWholeFeedPagesUnderNearMe() {
        List<String> walked = new ArrayList<>();
        String query = "?sort=near_me&near={near}&limit=3";
        Map<String, Object> page = feed(query, BAKU);
        walked.addAll(slugs(page));

        int guard = 0;
        while (nextCursor(page) != null) {
            // Including the null tail: the campaign with no location has a null sort
            // key, and a cursor that dropped the IS NULL branch would lose it from the
            // second page onwards without anything reporting a problem.
            page = feed(query + "&cursor={cursor}", BAKU, nextCursor(page));
            walked.addAll(slugs(page));
            if (++guard > 10) {
                throw new AssertionError("near_me paged for ever: " + walked);
            }
        }

        assertThat(walked).isEqualTo(FROM_BAKU);
        assertThat(new LinkedHashSet<>(walked)).hasSameSizeAs(walked);
    }

    @Test
    @DisplayName("a cursor carrying a different origin is refused rather than silently reshuffling")
    void aCursorIsBoundToItsOrigin() {
        String cursor = nextCursor(feed("?sort=near_me&near={near}&limit=2", BAKU));
        assertThat(cursor).isNotNull();

        // Every distance on the feed is measured from the origin, so a client that
        // moved between page one and page two would resume a keyset from a key that no
        // longer picks out the row it was written for. Same protection #42 gave the
        // decay clock and #44 gave the ranking weights.
        ResponseEntity<Map<String, Object>> response = get(
                "/v1/discover?sort=near_me&near={near}&limit=2&cursor={cursor}",
                new HttpHeaders(),
                ISTANBUL,
                cursor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "DISCOVERY_CURSOR_MISMATCH");
    }

    @Test
    @DisplayName("a cursor is bound to the radius as well as to the point")
    void aCursorIsBoundToTheRadius() {
        String cursor = nextCursor(feed("?sort=near_me&near={near}&radiusKm=400&limit=2", BAKU));
        assertThat(cursor).isNotNull();

        assertThat(get(
                                "/v1/discover?sort=near_me&near={near}&radiusKm=300&limit=2&cursor={cursor}",
                                new HttpHeaders(),
                                BAKU,
                                cursor)
                        .getBody())
                .containsEntry("code", "DISCOVERY_CURSOR_MISMATCH");
    }

    // -----------------------------------------------------------------------
    // Precision
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an origin is quantised to about a kilometre before anything sees it")
    void theOriginIsQuantised() {
        // Observable through the cursor, which is the only place the origin is visible
        // at all: the fingerprint hashes the canonical form, so two origins that
        // quantise to one point share a cursor and two that do not are refused.
        //
        // 40.409312,49.867094 is full GPS precision on the Baku seafront; it rounds to
        // 40.41,49.87, which is what BAKU already is.
        String cursor = nextCursor(feed("?sort=near_me&near={near}&limit=2", "40.409312,49.867094"));
        assertThat(cursor).isNotNull();

        assertThat(get(
                                "/v1/discover?sort=near_me&near={near}&limit=2&cursor={cursor}",
                                new HttpHeaders(),
                                BAKU,
                                cursor)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // And a point that is genuinely a different neighbourhood is a different query.
        assertThat(get(
                                "/v1/discover?sort=near_me&near={near}&limit=2&cursor={cursor}",
                                new HttpHeaders(),
                                "40.55,49.87",
                                cursor)
                        .getBody())
                .containsEntry("code", "DISCOVERY_CURSOR_MISMATCH");
    }

    @Test
    @DisplayName("two readers a few hundred metres apart share one cached response")
    void nearbyReadersShareACacheEntry() {
        // The reason the quantisation is not only a privacy decision: /v1/discover is
        // public and cached for a minute, and a cache key that varied at ten
        // centimetres would never be hit twice.
        String one = get("/v1/discover?sort=near_me&near={near}", new HttpHeaders(), "40.4093,49.8671")
                .getHeaders()
                .getETag();
        String other = get("/v1/discover?sort=near_me&near={near}", new HttpHeaders(), "40.4128,49.8702")
                .getHeaders()
                .getETag();

        assertThat(one).isNotNull().isEqualTo(other);
    }

    // -----------------------------------------------------------------------
    // What is refused
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("near me with no origin is refused, not resolved to another order")
    void nearMeWithoutAnOriginIsRefused() {
        // Unlike `best_match` with no `q`, which falls back to newest because a text
        // score over no text is zero for every campaign. A distance from no origin is
        // not zero, it is undefined, and a near-me control that quietly meant newest is
        // the accepted-and-ignored failure DiscoveryCapability exists to prevent.
        ResponseEntity<Map<String, Object>> response = get("/v1/discover?sort=near_me", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "DISCOVERY_VALUE_UNKNOWN");
        assertThat(response.getBody().get("meta").toString()).contains("near");
    }

    @Test
    @DisplayName("a radius with nothing to be a radius of is refused")
    void aRadiusWithoutAnOriginIsRefused() {
        assertThat(get("/v1/discover?radiusKm=50", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an origin that is not a point, or is off the earth, is refused")
    void aMalformedOriginIsRefused() {
        for (String near : List.of("40.41", "40.41,49.87,3", "north,east", "91,0", "0,181", "")) {
            assertThat(get("/v1/discover?sort=near_me&near={near}", new HttpHeaders(), near)
                            .getStatusCode())
                    .withFailMessage("near=%s was accepted", near)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // -----------------------------------------------------------------------
    // Visibility
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a campaign the public may not see is invisible under every location control")
    void locationIsNotAWayToSeeADraft() {
        var creator = Campaigns.creator(dataSource, "proximity-creator");
        for (String state : List.of("DRAFT", "SUBMITTED", "APPROVED", "SUSPENDED", "REJECTED")) {
            Campaigns.seed(dataSource, creator, "hidden-" + state.toLowerCase(java.util.Locale.ROOT))
                    .state(state)
                    .location("baki")
                    .insert();
        }

        Set<String> hidden = Set.of(
                "hidden-draft", "hidden-submitted", "hidden-approved", "hidden-suspended", "hidden-rejected");
        for (String query : List.of(
                "?country=AZ&limit=100",
                "?city=baki&limit=100",
                "?sort=near_me&near=" + BAKU + "&limit=100",
                "?sort=near_me&near=" + BAKU + "&radiusKm=5&limit=100")) {
            assertThat(slugs(feed(query)))
                    .withFailMessage("%s surfaced a hidden campaign", query)
                    .doesNotContainAnyElementsOf(hidden);
        }

        // And the facet panel does not leak their existence as a number either.
        assertThat(count(facets(""), "cities", "baki")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------

    /** Metres between two seeded locations, as the query rounds it. */
    private BigDecimal between(String from, String to) {
        return jdbc.queryForObject(
                """
                SELECT round(earth_distance(
                           ll_to_earth(a.latitude::float8, a.longitude::float8),
                           ll_to_earth(b.latitude::float8, b.longitude::float8))::numeric, 3)
                  FROM locations a, locations b
                 WHERE a.slug = ? AND b.slug = ?
                """,
                BigDecimal.class,
                from,
                to);
    }

    /**
     * Metres from the quantised {@link #BAKU} origin to a location, as the query rounds
     * it.
     *
     * <p>From the origin a request may actually send, not from Baku's own centroid: the
     * boundary the radius filter compares against is measured from the point the API
     * accepted, and a test that measured from anywhere else would be testing a
     * different number.
     */
    private BigDecimal fromBakuTo(String slug) {
        return jdbc.queryForObject(
                """
                SELECT round(earth_distance(
                           ll_to_earth(40.41::float8, 49.87::float8),
                           ll_to_earth(l.latitude::float8, l.longitude::float8))::numeric, 3)
                  FROM locations l WHERE l.slug = ?
                """,
                BigDecimal.class,
                slug);
    }

    /** The rendered label of one facet value. */
    private String nameOf(Map<String, Object> facets, String dimension, String slug) {
        return counts(facets, dimension).stream()
                .filter(entry -> slug.equals(entry.get("slug")))
                .map(entry -> (String) entry.get("name"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(dimension + " has no " + slug));
    }
}
