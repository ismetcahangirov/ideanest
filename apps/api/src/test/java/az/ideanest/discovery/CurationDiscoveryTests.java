package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.Campaigns;
import az.ideanest.support.Curations;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Curation inside the feed: {@code showOnly=featured} and §4.3's Programmes filter.
 *
 * <p>The point of this suite is <strong>composition</strong>, not the filters
 * themselves. Either one working alone is easy and is not what #48 promised: §4.3 puts
 * both on the same panel as category, tag, status, completion and the two amount
 * dimensions, D-12 makes the whole combination a shareable URL, and D-04 pages it. A
 * filter that works alone, drops out when a second is ticked, and disagrees with the
 * number beside it is the failure that reaches a backer as "the platform has nothing".
 *
 * <p>The fixture is therefore deliberately non-collinear: featured campaigns and
 * programme members overlap without coinciding, and both cut across the categories, so
 * a query that applied one filter where it meant the other would produce a different
 * answer from the one asserted.
 */
class CurationDiscoveryTests extends DiscoveryTestSupport {

    private UUID gamesFeatured;
    private UUID gamesPlain;
    private UUID comicsFeatured;
    private UUID artProgramme;
    private UUID suspendedFeatured;

    @BeforeEach
    void seedCampaignsAndCollections() {
        Campaigns.clear(dataSource);
        UUID creator = Campaigns.creator(dataSource, "curation-creator");
        Instant launched = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        gamesFeatured = campaign(creator, "games-featured", "games", "LIVE", "800.00", launched);
        gamesPlain = campaign(creator, "games-plain", "games", "LIVE", "200.00", launched);
        comicsFeatured = campaign(creator, "comics-featured", "comics", "SUCCESSFUL", "1200.00", launched);
        artProgramme = campaign(creator, "art-programme", "art", "LIVE", "400.00", launched);
        suspendedFeatured = campaign(creator, "suspended-featured", "games", "SUSPENDED", "900.00", launched);

        // A staff selection that grants the badge, holding two visible campaigns and
        // one the public may not see.
        UUID picks = Curations.collection(dataSource, "staff-picks")
                .kind("STAFF_SELECTION")
                .grantsBadge()
                .published()
                .title("az", "Redaksiya seçimi")
                .title("en", "Staff picks")
                .sortOrder(10)
                .insert();
        Curations.members(dataSource, picks, gamesFeatured, comicsFeatured, suspendedFeatured);

        // An open call, overlapping the staff picks on one campaign and not on the
        // other, so "featured" and "in this programme" are two different sets.
        UUID programme = Curations.collection(dataSource, "spring-call")
                .kind("OPEN_CALL")
                .published()
                .opensAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .closesAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .title("az", "Yaz çağırışı")
                .title("en", "Spring call")
                .sortOrder(20)
                .insert();
        Curations.members(dataSource, programme, gamesFeatured, artProgramme);

        // A themed collection that does NOT grant a badge, holding the one campaign
        // that is otherwise in nothing. Curating something is not the same as
        // endorsing it, and this is what asserts the difference.
        UUID theme = Curations.collection(dataSource, "quiet-theme")
                .kind("THEMED")
                .published()
                .sortOrder(30)
                .insert();
        Curations.members(dataSource, theme, gamesPlain);
    }

    private UUID campaign(
            UUID creator, String slug, String category, String state, String pledged, Instant launched) {
        return Campaigns.seed(dataSource, creator, slug)
                .category(category)
                .state(state)
                .goal("1000.00")
                .pledged(pledged)
                .backers(10)
                .launchedAt(launched)
                .deadline(launched.plus(30, ChronoUnit.DAYS))
                .tags("curated")
                .insert();
    }

    // ------------------------------------------------------------------
    // showOnly=featured
    // ------------------------------------------------------------------

    @Test
    @DisplayName("showOnly=featured is served rather than refused, and returns only badged campaigns")
    void featuredIsServed() {
        // This assertion used to be the opposite, and the change is #48 landing:
        // `showOnly=featured` was refused with a problem detail naming the issue,
        // because accepting it and ignoring it would have handed a backer who asked
        // for the platform's picks every campaign on it. There is still no third
        // option.
        assertThat(get("/v1/discover?showOnly=featured", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(slugs(feed("?limit=100&showOnly=featured")))
                .containsExactlyInAnyOrder("games-featured", "comics-featured");
    }

    @Test
    @DisplayName("membership of a collection that does not grant a badge is not being featured")
    void curatingIsNotEndorsing() {
        // `games-plain` is in a published themed collection and is not badged by it.
        // Without grants_badge the two would be the same thing, and every list a
        // curator ran would put the platform's name behind its contents.
        assertThat(slugs(feed("?limit=100&showOnly=featured"))).doesNotContain("games-plain");
    }

    @Test
    @DisplayName("a suspended campaign in a badge-granting collection is still invisible")
    void theBadgeIsNotAWayPastTheVisibilityPredicate() {
        assertThat(slugs(feed("?limit=100&showOnly=featured"))).doesNotContain("suspended-featured");
        // And through the second way into the table. Every fixture shares a blurb, so
        // `summary` is a term that matches all five and the only thing keeping the
        // suspended one out is the visibility predicate. (A tag is deliberately not
        // used here: tags are not in the search vector, so `q=curated` would match
        // nothing and the assertion would pass without testing anything.)
        assertThat(slugs(search("?limit=100&q=summary&showOnly=featured")))
                .doesNotContain("suspended-featured")
                .containsExactlyInAnyOrder("games-featured", "comics-featured");
    }

    @Test
    @DisplayName("an unpublished or expired collection badges nothing")
    void theBadgeExpiresWithTheCollectionThatGrantsIt() {
        UUID creator = Campaigns.creator(dataSource, "curation-creator");
        Instant launched = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID pending = campaign(creator, "pending-pick", "games", "LIVE", "100.00", launched);
        UUID lapsed = campaign(creator, "lapsed-pick", "games", "LIVE", "100.00", launched);

        UUID unpublished =
                Curations.collection(dataSource, "next-season").grantsBadge().insert();
        Curations.members(dataSource, unpublished, pending);

        UUID expired = Curations.collection(dataSource, "last-season")
                .grantsBadge()
                .published()
                .closesAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .insert();
        Curations.members(dataSource, expired, lapsed);

        assertThat(slugs(feed("?limit=100&showOnly=featured")))
                .doesNotContain("pending-pick", "lapsed-pick")
                .containsExactlyInAnyOrder("games-featured", "comics-featured");
    }

    @Test
    @DisplayName("showOnly=recommended is still refused, and says whose it is")
    void recommendedStaysRefused() {
        // Deliberately not served by curation. It is personalisation (§11.2's w6,
        // D-07), and answering it with the platform's staff picks would tell every
        // reader that an editorial decision was made for them personally.
        //
        // It survived #44 too, which is the case worth pinning: the composite ranking
        // landed and this did not, because §11.2's w6 is one of the five terms with no
        // data — /v1/discover is unauthenticated and publicly cached, so there is no
        // caller to personalise for. The issue in the body moved from #44 to D-07 with
        // that.
        ResponseEntity<Map<String, Object>> response =
                get("/v1/discover?showOnly=recommended", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "DISCOVERY_OPTION_UNSUPPORTED");
        assertThat(response.getBody().get("meta").toString()).contains("D-07");
    }

    // ------------------------------------------------------------------
    // The programmes filter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the programmes filter narrows the feed to one open call")
    void theProgrammeFilterNarrowsTheFeed() {
        assertThat(slugs(feed("?limit=100&programme=spring-call")))
                .containsExactlyInAnyOrder("games-featured", "art-programme");
    }

    @Test
    @DisplayName("a programme slug naming nothing is an empty feed, not an error")
    void anUnknownProgrammeIsAnEmptyFeed() {
        // A slug is content rather than a closed vocabulary, so a link shared after a
        // programme was renamed shows an empty feed rather than an error page — the
        // same rule category and tag slugs follow.
        assertThat(items(feed("?limit=100&programme=no-such-call"))).isEmpty();
        // And a collection that is not an open call is not a programme, however
        // published it is: "filter by the list you were picked for" and "filter by
        // having been picked" are two different controls.
        assertThat(items(feed("?limit=100&programme=staff-picks"))).isEmpty();
    }

    @Test
    @DisplayName("an unpublished open call cannot be used as a programme filter")
    void anUnpublishedProgrammeMatchesNothing() {
        UUID creator = Campaigns.creator(dataSource, "curation-creator");
        Instant launched = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID entrant = campaign(creator, "secret-entrant", "games", "LIVE", "100.00", launched);
        UUID secret = Curations.collection(dataSource, "secret-call")
                .kind("OPEN_CALL")
                .insert();
        Curations.members(dataSource, secret, entrant);

        // Otherwise a slug that leaked from an admin screen would be a way to read a
        // list the platform has not published.
        assertThat(items(feed("?limit=100&programme=secret-call"))).isEmpty();
    }

    // ------------------------------------------------------------------
    // Composition
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both filters compose with every other filter and with each other")
    void bothComposeWithEverythingElse() {
        // Featured, and in Games: comics-featured drops out on the category.
        assertThat(slugs(feed("?limit=100&showOnly=featured&category=games")))
                .containsExactly("games-featured");
        // Featured, and successful: games-featured drops out on the status.
        assertThat(slugs(feed("?limit=100&showOnly=featured&status=successful")))
                .containsExactly("comics-featured");
        // In the programme, and over 75% funded: art-programme is at 40%.
        assertThat(slugs(feed("?limit=100&programme=spring-call&completion=75_to_100")))
                .containsExactly("games-featured");
        // Both at once, which is the intersection and not the union.
        assertThat(slugs(feed("?limit=100&showOnly=featured&programme=spring-call")))
                .containsExactly("games-featured");
        // With a tag, an amount band, and free text on top of both.
        assertThat(slugs(feed("?limit=100&showOnly=featured&programme=spring-call&tag=curated&raisedBand=under_1000")))
                .containsExactly("games-featured");
        assertThat(slugs(search("?limit=100&q=summary&showOnly=featured&programme=spring-call")))
                .containsExactly("games-featured");
        // And a combination with an empty answer is empty rather than falling back to
        // one of the two filters.
        assertThat(items(feed("?limit=100&showOnly=featured&programme=spring-call&category=art"))).isEmpty();
    }

    @Test
    @DisplayName("both filters compose with every sort, and with the cursor")
    void bothComposeWithSortsAndPaging() {
        for (String sort : List.of("newest", "ending_soon", "most_funded", "most_backed", "popularity")) {
            assertThat(slugs(feed("?limit=100&showOnly=featured&sort=" + sort)))
                    .withFailMessage("showOnly=featured broke under sort=%s", sort)
                    .containsExactlyInAnyOrder("games-featured", "comics-featured");
            assertThat(slugs(feed("?limit=100&programme=spring-call&sort=" + sort)))
                    .withFailMessage("programme broke under sort=%s", sort)
                    .containsExactlyInAnyOrder("games-featured", "art-programme");
        }

        // The keyset branch is a different WHERE clause from the first page's, and it
        // would pass every assertion above if it had lost the curation predicate.
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            Map<String, Object> body =
                    feed("?limit=1&showOnly=featured&sort=most_funded" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(slugs(body));
            cursor = nextCursor(body);
            if (cursor == null) {
                break;
            }
        }
        assertThat(cursor).isNull();
        assertThat(seen).containsExactly("comics-featured", "games-featured");
        assertThat(new LinkedHashSet<>(seen)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Facets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the panel counts programmes and featured, and leaves out the collections nobody may see")
    void thePanelCountsTheNewDimensions() {
        Map<String, Object> facets = facets("");

        assertThat(count(facets, "programmes", "spring-call")).isEqualTo(2);
        assertThat(count(facets, "showOnly", "featured")).isEqualTo(2);
        // The staff selection is not an open call, so it is not a programme control;
        // the themed collection is not one either.
        assertThat(counts(facets, "programmes").stream()
                        .map(entry -> (String) entry.get("slug"))
                        .toList())
                .containsExactly("spring-call");
        // Labelled in the reader's language, through the same chain the taxonomy uses.
        assertThat(counts(facets, "programmes").get(0)).containsEntry("name", "Yaz çağırışı");
    }

    @Test
    @DisplayName("the new dimensions narrow the other facets, and are excluded from their own")
    void theNewDimensionsFollowTheExcludeOwnDimensionRule() {
        Map<String, Object> featured = facets("?showOnly=featured");

        // Every other dimension is counted under the featured filter.
        assertThat(count(featured, "categories", "games")).isEqualTo(1);
        assertThat(count(featured, "categories", "comics")).isEqualTo(1);
        assertThat(count(featured, "categories", "art")).isEqualTo(0);
        assertThat(count(featured, "tags", "curated")).isEqualTo(2);
        // And its own is not: the count is what would remain if the filter were
        // ticked, which is the same two campaigns as with no filter at all. A panel
        // that applied the filter to its own count would still read 2 here — so the
        // assertion that carries the rule is the programme one below, where applying
        // the dimension to itself would change the number.
        assertThat(count(featured, "showOnly", "featured")).isEqualTo(2);
        assertThat(count(featured, "programmes", "spring-call")).isEqualTo(1);

        Map<String, Object> programme = facets("?programme=spring-call");

        // Excluded from its own count: under the programme filter only two campaigns
        // remain, and the programme facet still says how many are in the programme
        // rather than how many of those are also in it.
        assertThat(count(programme, "programmes", "spring-call")).isEqualTo(2);
        assertThat(count(programme, "showOnly", "featured")).isEqualTo(1);
        assertThat(count(programme, "categories", "games")).isEqualTo(1);
        assertThat(count(programme, "categories", "art")).isEqualTo(1);
        assertThat(count(programme, "categories", "comics")).isEqualTo(0);
    }

    @Test
    @DisplayName("every facet count equals the number of cards the feed returns for it")
    void theNumbersAgreeWithTheFeed() {
        // A panel whose numbers disagree with the results is worse than a panel with
        // no numbers.
        for (String query : List.of("", "?category=games", "?status=live", "?showOnly=featured")) {
            Map<String, Object> facets = facets(query);
            long featured = count(facets, "showOnly", "featured");
            long programme = count(facets, "programmes", "spring-call");

            String feedQuery = query.isEmpty() ? "?limit=100" : "?limit=100&" + query.substring(1);
            assertThat(items(feed(feedQuery + "&showOnly=featured")))
                    .withFailMessage("featured facet disagreed with the feed for '%s'", query)
                    .hasSize((int) featured);
            assertThat(items(feed(feedQuery + "&programme=spring-call")))
                    .withFailMessage("programme facet disagreed with the feed for '%s'", query)
                    .hasSize((int) programme);
        }
    }
}
