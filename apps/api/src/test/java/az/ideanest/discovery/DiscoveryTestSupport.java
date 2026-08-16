package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * What the discovery suites share: the two endpoints, and a way to read them.
 *
 * <p>Extends {@code AbstractIntegrationTest} rather than annotating anything of its
 * own, so that every discovery suite still shares the one Spring context and the one
 * PostgreSQL container with the rest of the run.
 */
abstract class DiscoveryTestSupport extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> BODY =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected DataSource dataSource;

    protected ResponseEntity<Map<String, Object>> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), BODY);
    }

    /** A feed, asserted to have succeeded, so a test reads about ordering rather than about status codes. */
    protected Map<String, Object> feed(String query) {
        ResponseEntity<Map<String, Object>> response = get("/v1/discover" + query, new HttpHeaders());
        assertThat(response.getStatusCode())
                .withFailMessage("GET /v1/discover%s answered %s: %s", query, response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected Map<String, Object> facets(String query) {
        ResponseEntity<Map<String, Object>> response = get("/v1/discover/facets" + query, new HttpHeaders());
        assertThat(response.getStatusCode())
                .withFailMessage("GET /v1/discover/facets%s answered %s", query, response.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** The slugs on a page, in the order they came back. */
    protected List<String> slugs(Map<String, Object> feed) {
        return items(feed).stream().map(item -> (String) item.get("slug")).toList();
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> items(Map<String, Object> feed) {
        return (List<Map<String, Object>>) feed.get("items");
    }

    /**
     * The next page's cursor, or null at the end of the feed.
     *
     * <p>Absent rather than null in the body — the service omits null properties — so
     * this is also the assertion that "no more pages" is expressed as absence.
     */
    protected String nextCursor(Map<String, Object> feed) {
        return (String) feed.get("nextCursor");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> counts(Map<String, Object> facets, String dimension) {
        return (List<Map<String, Object>>) facets.get(dimension);
    }

    /** One facet value's count, by its key. */
    protected long count(Map<String, Object> facets, String dimension, String value) {
        return counts(facets, dimension).stream()
                .filter(entry -> value.equals(entry.get("value")) || value.equals(entry.get("slug")))
                .map(entry -> ((Number) entry.get("count")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "facet '" + dimension + "' has no value '" + value + "': " + counts(facets, dimension)));
    }
}
