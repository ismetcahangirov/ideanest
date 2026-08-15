package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * endpoint, and the two things most likely to break it are silent — an
 * authentication rule that starts requiring a token, and a conditional request
 * that stops matching and quietly costs every client the whole tree on every page
 * load.
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

    @Test
    @DisplayName("the seeded taxonomy is readable without signing in")
    void theTreeIsPublic() {
        ResponseEntity<List<Map<String, Object>>> response = categories(new HttpHeaders());

        // No Authorization header at all. A creator picking a category is signed
        // in, but discovery's navigation is not, and the two read the same list.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> tree = response.getBody();
        assertThat(tree).isNotEmpty();

        // Both names, because choosing between them is Accept-Language's job and
        // #123 owns that for the whole API.
        assertThat(tree.getFirst()).containsKeys("id", "slug", "nameAz", "nameEn", "subcategories");

        // Nested rather than a second request: a dependent <select> that waits for
        // one more round trip appears empty for a moment.
        assertThat(tree)
                .anySatisfy(category -> assertThat((List<?>) category.get("subcategories"))
                        .isNotEmpty());
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

        // Stable across calls. A tag derived from something that moves — a hash
        // that varies per instance, a timestamp — revalidates to a 200 every time
        // and is worse than no tag, because it looks like it is working.
        assertThat(categories(new HttpHeaders()).getHeaders().getETag()).isEqualTo(etag);
    }
}
