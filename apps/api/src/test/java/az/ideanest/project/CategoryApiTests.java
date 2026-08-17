package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The category tree over HTTP.
 *
 * <p>Small, and worth having: the editor's category field is unusable without this
 * endpoint, and the three things most likely to break it are silent — an
 * authentication rule that starts requiring a token, a conditional request that
 * stops matching and quietly costs every client the whole tree on every page
 * load, and a conditional request that matches when it should not and serves one
 * language's names to somebody who asked for another.
 */
class CategoryApiTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<List<Map<String, Object>>> categories(HttpHeaders headers) {
        return rest.exchange(
                "/v1/categories",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> categoriesIn(String acceptLanguage) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        return categories(headers);
    }

    /** The one category every assertion below is about, found by slug. */
    private static Map<String, Object> art(List<Map<String, Object>> tree) {
        return tree.stream()
                .filter(category -> "art".equals(category.get("slug")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the seeded taxonomy has no 'art' category"));
    }

    @Test
    @DisplayName("the seeded taxonomy is readable without signing in")
    void theTreeIsPublic() {
        ResponseEntity<List<Map<String, Object>>> response = categories(new HttpHeaders());

        // No Authorization header at all. A creator picking a category is signed
        // in, but discovery's navigation is not, and the two read the same list.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> tree = response.getBody();
        assertThat(tree).isNotEmpty();

        // `name` is the one to render and `names` is every translation held, for
        // a client with its own language switcher. `nameAz` and `nameEn` are the
        // interim columns of V6 and are still here for one release, because the
        // web application deploys separately from this service.
        assertThat(tree.getFirst()).containsKeys("id", "slug", "name", "names", "nameAz", "nameEn", "subcategories");

        // Nested rather than a second request: a dependent <select> that waits for
        // one more round trip appears empty for a moment.
        assertThat(tree)
                .anySatisfy(category -> assertThat((List<?>) category.get("subcategories"))
                        .isNotEmpty());
    }

    @Test
    @DisplayName("a client that asks for English is answered in English")
    void theRequestedLanguageIsUsed() {
        Map<String, Object> art = art(categoriesIn("en").getBody());

        assertThat(art.get("name")).isEqualTo("Art");
        // Every translation held, so a client rendering its own switcher does not
        // need one request per language.
        assertThat(art.get("names")).isEqualTo(Map.of("az", "İncəsənət", "en", "Art"));

        List<?> subcategories = (List<?>) art.get("subcategories");
        assertThat(subcategories).isNotEmpty();
        assertThat(subcategories).allSatisfy(raw -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> subcategory = (Map<String, Object>) raw;
            assertThat(subcategory.get("name")).isNotNull();
        });
    }

    @Test
    @DisplayName("a language nobody has translated yet is answered in Azerbaijani")
    void anUntranslatedLanguageFallsBack() {
        // Russian is phase 1 in §21.1 and deliberately unseeded: the point of the
        // translation table is that it arrives as data rather than as a release.
        // Until it does, a Russian-speaking visitor sees the platform's primary
        // language rather than a blank navigation.
        Map<String, Object> art = art(categoriesIn("ru").getBody());

        assertThat(art.get("name")).isEqualTo("İncəsənət");

        @SuppressWarnings("unchecked")
        Map<String, String> names = (Map<String, String>) art.get("names");
        assertThat(names).containsOnlyKeys("az", "en");
    }

    @Test
    @DisplayName("the tree says it varies by language")
    void theResponseVariesByLanguage() {
        // Without this a shared cache stores whichever language it saw first and
        // serves it to everybody, which is worse than not caching at all: a
        // creator is shown a category list they cannot read and has no way to ask
        // for another.
        assertThat(categoriesIn("en").getHeaders().getVary()).contains(HttpHeaders.ACCEPT_LANGUAGE);
    }

    @Test
    @DisplayName("a client holding the current tree is told nothing changed")
    void aConditionalRequestIsAnswered304() {
        ResponseEntity<List<Map<String, Object>>> first = categories(new HttpHeaders());
        String etag = first.getHeaders().getETag();

        // The tag is what makes an hour of caching cost one small request rather
        // than the whole tree, so its absence is a regression worth failing on.
        assertThat(etag).isNotBlank();
        assertThat(first.getHeaders().getCacheControl()).contains("max-age=3600");

        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(etag);

        ResponseEntity<List<Map<String, Object>>> second = categories(conditional);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();
        // On the 304 as well as on the 200. A cache that learns the rule only
        // from the full response has already stored the wrong thing by then.
        assertThat(second.getHeaders().getVary()).contains(HttpHeaders.ACCEPT_LANGUAGE);

        // Stable across calls. A tag derived from something that moves — a hash
        // that varies per instance, a timestamp — revalidates to a 200 every time
        // and is worse than no tag, because it looks like it is working.
        assertThat(categories(new HttpHeaders()).getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    @DisplayName("two languages of one tree do not share a tag")
    void theTagVariesWithTheLanguage() {
        String azerbaijani = categoriesIn("az").getHeaders().getETag();
        String english = categoriesIn("en").getHeaders().getETag();

        assertThat(azerbaijani).isNotBlank();
        assertThat(english).isNotBlank();

        // A shared tag means a client that asked for English revalidates an
        // Azerbaijani copy, is told 304, and renders the wrong language for an
        // hour. Vary tells a shared cache the same thing; this is what makes the
        // client's own conditional request correct.
        assertThat(english).isNotEqualTo(azerbaijani);

        // And stable within one language, or the tag buys nothing.
        assertThat(categoriesIn("en").getHeaders().getETag()).isEqualTo(english);

        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(azerbaijani);
        conditional.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
        assertThat(categories(conditional).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
