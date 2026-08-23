package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /v1/locations}: §4.3's location vocabulary, published so a client can offer it
 * (#276).
 *
 * <p>What this suite is actually protecting is the reason the endpoint exists. V16 seeded a
 * closed vocabulary and gave it no index, so the only ways to put a city on a form were a
 * free-text box that answers 400 for every spelling but one, or eighteen names copied into
 * {@code apps/web} — which is what §4.3's "the taxonomy is data, not code" refuses. These
 * tests pin the three properties that make the published list usable instead: it is
 * complete, it is named in the reader's language with a fallback that is never empty, and it
 * revalidates per language rather than across languages.
 */
class LocationApiTests extends DiscoveryTestSupport {

    /** V16 seeds eighteen places and nothing writes to the table. */
    private static final int SEEDED_PLACES = 18;

    @Test
    @DisplayName("lists every seeded place, because a partial vocabulary is worse than none")
    void listsEverySeededPlace() {
        List<Map<String, Object>> places = places(get("/v1/locations", new HttpHeaders()));

        assertThat(places).hasSize(SEEDED_PLACES);
        assertThat(places).allSatisfy(place -> {
            assertThat(place.get("slug")).asString().isNotBlank();
            assertThat(place.get("name")).asString().isNotBlank();
        });
        assertThat(places.stream().map(place -> place.get("slug")))
                .contains("baki", "gence", "seki");
    }

    @Test
    @DisplayName("answers the endonym when the reader asks for Azerbaijani")
    void answersTheEndonym() {
        assertThat(nameOf(placesIn("az"), "baki")).isEqualTo("Bakı");
        assertThat(nameOf(placesIn("az"), "gence")).isEqualTo("Gəncə");
    }

    @Test
    @DisplayName("falls back to the endonym rather than to the slug for a language with no row")
    void fallsBackToTheEndonymRatherThanTheSlug() {
        // The middle link of the chain, and the one a single join with a COALESCE would
        // skip: a reader who asked for Russian gets the Azerbaijani name of a place that
        // has no Russian row, because that is at least a name somebody wrote. The slug is
        // the last resort and this vocabulary should never reach it.
        List<Map<String, Object>> russian = placesIn("ru");

        assertThat(russian).hasSize(SEEDED_PLACES);
        assertThat(russian).allSatisfy(place ->
                assertThat(place.get("name")).asString().isNotEqualTo(place.get("slug")));
    }

    @Test
    @DisplayName("resolves an unsupported or unparseable language instead of failing")
    void resolvesAnUnsupportedLanguage() {
        // A public cacheable read: a header the caller got wrong is not a reason to have no
        // list at all.
        assertThat(placesIn("zz")).hasSize(SEEDED_PLACES);
        assertThat(placesIn("!! not a language")).hasSize(SEEDED_PLACES);
    }

    @Test
    @DisplayName("revalidates per language, so one language's tag never answers another's")
    void revalidatesPerLanguage() {
        ResponseEntity<Map<String, Object>> azerbaijani = get("/v1/locations", accepting("az"));
        String tag = azerbaijani.getHeaders().getETag();

        assertThat(azerbaijani.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tag).isNotBlank();
        assertThat(azerbaijani.getHeaders().getFirst(HttpHeaders.VARY))
                .isEqualTo(HttpHeaders.ACCEPT_LANGUAGE);
        assertThat(azerbaijani.getHeaders().getCacheControl()).contains("max-age=3600");

        // Same language, same tag: 304, and Vary is on the 304 too — a cache that kept the
        // Azerbaijani list and replayed it to a client that asked for Russian is exactly
        // what that header prevents, and it has to be on both answers to prevent it.
        HttpHeaders sameLanguage = accepting("az");
        sameLanguage.setIfNoneMatch(tag);
        ResponseEntity<Map<String, Object>> revalidated = get("/v1/locations", sameLanguage);
        assertThat(revalidated.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(revalidated.getHeaders().getFirst(HttpHeaders.VARY))
                .isEqualTo(HttpHeaders.ACCEPT_LANGUAGE);

        // A different language holding the first one's tag is served the list, not a 304.
        HttpHeaders otherLanguage = accepting("ru");
        otherLanguage.setIfNoneMatch(tag);
        assertThat(get("/v1/locations", otherLanguage).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("is readable without a token, because it is reference data")
    void isReadableWithoutAToken() {
        // The first caller is the profile editor, which is authenticated. That is a fact
        // about who asks first rather than about who may.
        assertThat(get("/v1/locations", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static HttpHeaders accepting(String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        return headers;
    }

    private List<Map<String, Object>> placesIn(String language) {
        return places(get("/v1/locations", accepting(language)));
    }

    private List<Map<String, Object>> places(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        return items(body, "items");
    }

    private static String nameOf(List<Map<String, Object>> places, String slug) {
        return places.stream()
                .filter(place -> slug.equals(place.get("slug")))
                .map(place -> String.valueOf(place.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no place with slug " + slug));
    }
}
