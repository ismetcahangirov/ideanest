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

    /**
     * @param variables values for {@code {name}} placeholders in the path, encoded by
     *     Spring. <strong>The only correct way to put a search term in a URL from a
     *     test</strong>: "seçənək" percent-encodes to nine bytes of UTF-8, and a test
     *     that pasted it into the string would be testing whichever encoding the file
     *     happened to be saved in
     */
    protected ResponseEntity<Map<String, Object>> get(String path, HttpHeaders headers, Object... variables) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), BODY, variables);
    }

    /** A feed, asserted to have succeeded, so a test reads about ordering rather than about status codes. */
    protected Map<String, Object> feed(String query, Object... variables) {
        return ok("/v1/discover", query, variables);
    }

    /** The same, against {@code GET /v1/search}. */
    protected Map<String, Object> search(String query, Object... variables) {
        return ok("/v1/search", query, variables);
    }

    protected Map<String, Object> facets(String query, Object... variables) {
        return ok("/v1/discover/facets", query, variables);
    }

    /** {@code GET /v1/search/suggest}, whose body is an object with one array in it. */
    protected List<Map<String, Object>> suggestions(String query, Object... variables) {
        return items(ok("/v1/search/suggest", query, variables), "items");
    }

    private Map<String, Object> ok(String path, String query, Object... variables) {
        ResponseEntity<Map<String, Object>> response = get(path + query, new HttpHeaders(), variables);
        assertThat(response.getStatusCode())
                .withFailMessage(
                        "GET %s%s %s answered %s: %s",
                        path, query, List.of(variables), response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** The slugs on a page, in the order they came back. */
    protected List<String> slugs(Map<String, Object> feed) {
        return items(feed).stream().map(item -> (String) item.get("slug")).toList();
    }

    protected List<Map<String, Object>> items(Map<String, Object> feed) {
        return items(feed, "items");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> items(Map<String, Object> body, String property) {
        return (List<Map<String, Object>>) body.get(property);
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
