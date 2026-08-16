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
 * {@code GET /v1/collections} and {@code GET /v1/collections/{slug}}: D-08.
 *
 * <p>Two properties carry this suite, and they are the two that are expensive to get
 * wrong.
 *
 * <p><strong>An editorial decision in progress is not readable.</strong> Which
 * campaigns the platform is about to put its name behind — and by implication which it
 * passed over — is confidential until it is published, so an unpublished collection and
 * one outside its window answer 404 and not 403. A 403 confirms the slug exists to
 * anybody who guesses it.
 *
 * <p><strong>Membership and visibility are two different things.</strong> A curator
 * picks a campaign; trust and safety suspends it a week later; the membership row stays,
 * because deleting it would rewrite the record to say the campaign was never chosen. The
 * read is what has to exclude it, from the cards <em>and</em> from the count — a count
 * that included it would tell a reader the page has more on it than it does.
 */
class CollectionApiTests extends DiscoveryTestSupport {

    private UUID live;
    private UUID successful;
    private UUID draft;
    private UUID suspended;

    @BeforeEach
    void seedCampaigns() {
        Campaigns.clear(dataSource);
        UUID creator = Campaigns.creator(dataSource, "collection-creator");
        Instant launched = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        live = campaign(creator, "live-one", "LIVE", launched);
        successful = campaign(creator, "successful-one", "SUCCESSFUL", launched);
        draft = campaign(creator, "draft-one", "DRAFT", null);
        suspended = campaign(creator, "suspended-one", "SUSPENDED", launched);
    }

    private UUID campaign(UUID creator, String slug, String state, Instant launched) {
        Campaigns.Seed seed =
                Campaigns.seed(dataSource, creator, slug).state(state).category("games").goal("1000.00");
        if (launched != null) {
            seed.launchedAt(launched).deadline(launched.plus(30, ChronoUnit.DAYS));
        }
        return seed.insert();
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unpublished collection is 404 to the public, not 403")
    void anUnpublishedCollectionIsInvisible() {
        Curations.collection(dataSource, "spring-2027").insert();

        ResponseEntity<Map<String, Object>> response = get("/v1/collections/spring-2027", new HttpHeaders());

        // 403 would confirm the slug exists, which is the whole thing being protected.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "COLLECTION_NOT_FOUND");
        // And the body does not echo the slug back, so a probe learns nothing from it.
        assertThat(response.getBody().get("detail").toString()).doesNotContain("spring-2027");
        assertThat(slugsOf(ok("/v1/collections"))).doesNotContain("spring-2027");
    }

    @Test
    @DisplayName("a collection whose window has not opened is 404, and one whose window closed is too")
    void aCollectionOutsideItsWindowIsInvisible() {
        Instant now = Instant.now();
        Curations.collection(dataSource, "not-yet")
                .kind("OPEN_CALL")
                .published()
                .opensAt(now.plus(2, ChronoUnit.DAYS))
                .insert();
        Curations.collection(dataSource, "already-closed")
                .kind("OPEN_CALL")
                .published()
                .opensAt(now.minus(30, ChronoUnit.DAYS))
                .closesAt(now.minus(1, ChronoUnit.DAYS))
                .insert();
        Curations.collection(dataSource, "open-now")
                .kind("OPEN_CALL")
                .published()
                .opensAt(now.minus(1, ChronoUnit.DAYS))
                .closesAt(now.plus(30, ChronoUnit.DAYS))
                .insert();

        for (String slug : List.of("not-yet", "already-closed")) {
            assertThat(get("/v1/collections/" + slug, new HttpHeaders()).getStatusCode())
                    .withFailMessage("%s was visible", slug)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(get("/v1/collections/open-now", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(slugsOf(ok("/v1/collections"))).containsExactly("open-now");
    }

    @Test
    @DisplayName("a suspended or draft campaign in a published collection is neither returned nor counted")
    void aHiddenCampaignInAVisibleCollectionIsNotReturned() {
        UUID collection = Curations.collection(dataSource, "mixed").published().insert();
        Curations.members(dataSource, collection, live, draft, suspended, successful);

        Map<String, Object> body = ok("/v1/collections/mixed");

        assertThat(slugs(body)).containsExactly("live-one", "successful-one");
        // The count is the quieter half of the same leak: the campaigns are not shown,
        // only counted, and the number is what a reader uses to decide the page has
        // more on it than it does.
        assertThat(collectionOf(body).get("projectCount")).isEqualTo(2);
        // The membership rows are still there. Removing them would rewrite the
        // editorial record to say the campaign was never chosen.
        assertThat(membershipCount(collection)).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the cards come back in the curator's order, not the feed's")
    void curatorOrderIsPreserved() {
        UUID creator = Campaigns.creator(dataSource, "collection-creator");
        Instant launched = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        // Launched most recently, so `newest` would put it first and the curator's
        // order puts it last. Without this the assertion would pass under either.
        UUID newest = campaign(creator, "newest-one", "LIVE", launched);

        UUID collection = Curations.collection(dataSource, "edited").published().insert();
        Curations.members(dataSource, collection, successful, live, newest);

        assertThat(slugs(ok("/v1/collections/edited")))
                .containsExactly("successful-one", "live-one", "newest-one");
    }

    @Test
    @DisplayName("a cursor walk visits every campaign once, in the curator's order")
    void theOrderSurvivesACursorWalk() {
        UUID creator = Campaigns.creator(dataSource, "collection-creator");
        Instant launched = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<UUID> members = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            // Named in reverse so that the curator's order is not any other order the
            // query could accidentally produce.
            String slug = "walk-" + (9 - index);
            members.add(campaign(creator, slug, "LIVE", launched));
            expected.add(slug);
        }
        UUID collection = Curations.collection(dataSource, "walkable").published().insert();
        Curations.members(dataSource, collection, members.toArray(UUID[]::new));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            Map<String, Object> body = ok("/v1/collections/walkable?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(slugs(body));
            cursor = nextCursor(body);
            if (cursor == null) {
                break;
            }
        }

        assertThat(cursor).isNull();
        // Exactly, in order: a keyset that dropped its tiebreaker would still return
        // seven rows and would return them in an order the curator did not choose.
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(new LinkedHashSet<>(seen)).hasSize(expected.size());
    }

    @Test
    @DisplayName("a cursor from one collection is refused by another")
    void aCursorIsBoundToItsCollection() {
        UUID creator = Campaigns.creator(dataSource, "collection-creator");
        Instant launched = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID first = Curations.collection(dataSource, "first").published().insert();
        UUID second = Curations.collection(dataSource, "second").published().insert();
        Curations.members(
                dataSource,
                first,
                campaign(creator, "first-a", "LIVE", launched),
                campaign(creator, "first-b", "LIVE", launched));
        Curations.members(
                dataSource,
                second,
                campaign(creator, "second-a", "LIVE", launched),
                campaign(creator, "second-b", "LIVE", launched));

        String cursor = nextCursor(ok("/v1/collections/first?limit=1"));
        assertThat(cursor).isNotNull();

        ResponseEntity<Map<String, Object>> response =
                get("/v1/collections/second?limit=1&cursor=" + cursor, new HttpHeaders());

        // Answering page one instead would silently restart the scroll, and the client
        // would append somebody else's list to the one on screen.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "DISCOVERY_CURSOR_MISMATCH");
    }

    // ------------------------------------------------------------------
    // Localisation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the title and description fall back exactly as the taxonomy's names do")
    void localeFallbackMatchesTheTaxonomy() {
        Curations.collection(dataSource, "seasonal")
                .published()
                .title("az", "Yaz seçimi")
                .description("az", "Yazda diqqətə layiq layihələr")
                .title("en", "Spring selection")
                .description("en", "Projects worth a look this spring")
                .insert();

        // The requested locale, when there is a row for it.
        assertThat(collectionOf(ok("/v1/collections/seasonal", "en"))).containsEntry("title", "Spring selection");
        // Then az, which §21.1 makes the primary language: Russian is a supported
        // locale with no row here, and the answer is Azerbaijani rather than English.
        assertThat(collectionOf(ok("/v1/collections/seasonal", "ru"))).containsEntry("title", "Yaz seçimi");
        assertThat(collectionOf(ok("/v1/collections/seasonal", "ru")))
                .containsEntry("description", "Yazda diqqətə layiq layihələr");
        // And an unsupported language resolves to the primary one rather than failing.
        assertThat(collectionOf(ok("/v1/collections/seasonal", "de-DE"))).containsEntry("title", "Yaz seçimi");
    }

    @Test
    @DisplayName("a collection with no copy at all renders its slug, and no description")
    void theSlugIsTheLastResortForTheTitleAndNotForTheDescription() {
        // Not reachable through the admin API — CurationService requires an az title —
        // so this is the hand-edited row the fallback chain's last step exists for.
        Curations.collection(dataSource, "handmade-row").published().withoutCopy().insert();

        Map<String, Object> collection = collectionOf(ok("/v1/collections/handmade-row"));

        // A readable handle rather than an empty heading.
        assertThat(collection).containsEntry("title", "handmade-row");
        // There is no readable handle for a paragraph, so the standfirst is absent
        // rather than being the slug a second time.
        assertThat(collection).doesNotContainKey("description");
    }

    // ------------------------------------------------------------------
    // Caching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both reads carry an ETag, revalidate to 304, and vary by language")
    void publicReadsAreCacheable() {
        Curations.collection(dataSource, "cacheable")
                .published()
                .title("az", "Seçim")
                .title("en", "Selection")
                .insert();

        for (String path : List.of("/v1/collections", "/v1/collections/cacheable")) {
            ResponseEntity<Map<String, Object>> first = get(path, headers("en"));
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            String etag = first.getHeaders().getETag();
            assertThat(etag).isNotNull();
            assertThat(first.getHeaders().getCacheControl()).contains("max-age=60");
            assertThat(first.getHeaders().getVary()).contains(HttpHeaders.ACCEPT_LANGUAGE);

            HttpHeaders conditional = headers("en");
            conditional.setIfNoneMatch(etag);
            ResponseEntity<Map<String, Object>> second = get(path, conditional);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
            assertThat(second.getHeaders().getVary()).contains(HttpHeaders.ACCEPT_LANGUAGE);

            // The same tag against a different language must not revalidate: a shared
            // cache that returned Azerbaijani copy to a client that asked for English
            // is worse than no cache.
            HttpHeaders otherLanguage = headers("az");
            otherLanguage.setIfNoneMatch(etag);
            assertThat(get(path, otherLanguage).getStatusCode())
                    .withFailMessage("%s revalidated across languages", path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("the collections index is ordered by placement and lists nothing unpublished")
    void theIndexIsOrderedAndPublishedOnly() {
        Curations.collection(dataSource, "third").published().sortOrder(30).insert();
        Curations.collection(dataSource, "first").published().sortOrder(10).insert();
        Curations.collection(dataSource, "second").published().sortOrder(20).insert();
        Curations.collection(dataSource, "hidden").sortOrder(5).insert();

        assertThat(slugsOf(ok("/v1/collections"))).containsExactly("first", "second", "third");
    }

    // ------------------------------------------------------------------

    private Map<String, Object> ok(String path) {
        return ok(path, null);
    }

    private Map<String, Object> ok(String path, String language) {
        ResponseEntity<Map<String, Object>> response = get(path, headers(language));
        assertThat(response.getStatusCode())
                .withFailMessage("GET %s answered %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders headers(String language) {
        HttpHeaders headers = new HttpHeaders();
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectionOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("collection");
    }

    private List<String> slugsOf(Map<String, Object> index) {
        return items(index).stream().map(item -> (String) item.get("slug")).toList();
    }

    private long membershipCount(UUID collectionId) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM collection_projects WHERE collection_id = ?",
                        Long.class,
                        collectionId);
    }
}
