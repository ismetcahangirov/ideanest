package az.ideanest.admin;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.user.infrastructure.UserRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The trail is ordered by the column it displays — #404.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code /admin/audit} describes itself as newest first, and AD-14 on the console index
 * says the same. It was ordered by {@code id}, on the argument that a UUID v7 carries the
 * millisecond it was minted in (§7.3), so the key and {@code occurred_at} say the same
 * thing and only one of them is unique.
 *
 * <p>They are written by two different clocks. The identifier is minted in the application
 * when {@code AuditEntry.record} builds the row; {@code occurred_at} is
 * {@code DEFAULT now()} and is taken when the insert lands. A transaction that mints early
 * and commits late, two instances whose clocks differ, and a backdated import all put the
 * two orders out of step — and the screen renders the timestamp while the query ordered by
 * the key. Walked in a browser against the local seed, the first fourteen rows under
 * "newest first" were from the previous month and that morning's eight privileged actions
 * began at position fifteen.
 *
 * <p>On an audit surface that is not a rough edge. An investigator who opens the log and
 * sees August at the top has no reason to scroll for this morning.
 *
 * <h2>How this suite reproduces it</h2>
 *
 * <p>By writing rows the way the two clocks disagree: three inserts whose
 * {@code occurred_at} runs backwards while their identifiers run forwards. Every row goes
 * in through SQL, because {@code occurred_at} is {@code insertable = false} on the entity
 * precisely so that no caller can choose it — which is the right rule and makes the
 * disagreement unreachable through the application.
 *
 * <p><strong>Nothing is cleaned up, and nothing can be.</strong> V21 puts a trigger on
 * {@code audit_logs} that raises on DELETE and TRUNCATE, so every row this suite writes is
 * there for the rest of the run. They carry an entity kind no production action uses and
 * every assertion filters to it, so the rows are invisible to any other suite and to the
 * unfiltered read this suite also makes.
 */
class AuditTrailOrderingApiTests extends AbstractIntegrationTest {

    /** The address {@code application-test.yml} lists as staff. */
    private static final String STAFF_EMAIL = "moderator@ideanest.test";

    private static final String PASSWORD = "a-long-enough-password";

    /**
     * An entity kind nothing on the platform writes.
     *
     * <p>{@code AUDIT_ENTITY_TYPES} on the console side lists the six that exist and this
     * is none of them, so these rows are reachable only by asking for them by name — which
     * is what makes writing to an append-only table from a test suite survivable.
     */
    private static final String PROBE = "ordering-probe";

    private static final String TRAIL = "/v1/admin/audit";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("rows come back by when they happened, not by the order their keys were minted")
    void theTrailIsOrderedByTheTimestampItDisplays() {
        UUID subject = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Minted in ascending key order and stamped in descending time order, which is
        // exactly the disagreement the old ordering could not see.
        UUID oldest = write(subject, "probe.one", now.minus(30, ChronoUnit.DAYS));
        UUID middle = write(subject, "probe.two", now.minus(7, ChronoUnit.DAYS));
        UUID newest = write(subject, "probe.three", now.minus(1, ChronoUnit.MINUTES));

        List<Map<String, Object>> entries = entriesOf(trail("?entityType=" + PROBE + "&entityId=" + subject));

        assertThat(entries.stream().map(row -> row.get("id")).toList())
                .as("newest first, by occurredAt")
                .containsExactly(newest.toString(), middle.toString(), oldest.toString());
    }

    @Test
    @DisplayName("what the page says and what it is ordered by are the same fact")
    void theRenderedTimestampsDescend() {
        UUID subject = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        write(subject, "probe.one", now.minus(30, ChronoUnit.DAYS));
        write(subject, "probe.two", now.minus(7, ChronoUnit.DAYS));
        write(subject, "probe.three", now.minus(1, ChronoUnit.MINUTES));

        List<Instant> shown = entriesOf(trail("?entityType=" + PROBE + "&entityId=" + subject)).stream()
                .map(row -> Instant.parse((String) row.get("occurredAt")))
                .toList();

        // The assertion the screen's own heading makes. Before #404 it failed against the
        // seed and nobody was checking it here.
        assertThat(shown).isSortedAccordingTo((left, right) -> right.compareTo(left));
    }

    @Test
    @DisplayName("paging continues where the page ended rather than repeating or skipping")
    void pagingWalksTheTrailInTheSameOrder() {
        UUID subject = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID oldest = write(subject, "probe.one", now.minus(30, ChronoUnit.DAYS));
        UUID middle = write(subject, "probe.two", now.minus(7, ChronoUnit.DAYS));
        UUID newest = write(subject, "probe.three", now.minus(1, ChronoUnit.MINUTES));

        Map<String, Object> first = trail("?entityType=" + PROBE + "&entityId=" + subject + "&limit=2");
        assertThat(entriesOf(first).stream().map(row -> row.get("id")).toList())
                .containsExactly(newest.toString(), middle.toString());
        assertThat(first.get("nextCursor")).as("a full page may have more behind it").isNotNull();

        Map<String, Object> second =
                trail("?entityType=" + PROBE + "&entityId=" + subject + "&limit=2&after=" + first.get("nextCursor"));
        assertThat(entriesOf(second).stream().map(row -> row.get("id")).toList())
                .containsExactly(oldest.toString());
        assertThat(second.get("nextCursor")).as("a short page is the end").isNull();
    }

    @Test
    @DisplayName("rows sharing one instant are not hidden behind each other by the cursor")
    void tiedTimestampsAreBrokenByTheIdentifier() {
        UUID subject = UUID.randomUUID();
        // Two rows written by one transaction share a timestamp, which is why the cursor
        // is a pair rather than an instant: on a tie an instant-only cursor either repeats
        // the row or drops it, depending on which side of the boundary it falls.
        Instant tied = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.MINUTES);
        UUID first = write(subject, "probe.tied-a", tied);
        UUID second = write(subject, "probe.tied-b", tied);

        Map<String, Object> page = trail("?entityType=" + PROBE + "&entityId=" + subject + "&limit=1");
        List<String> seen = new java.util.ArrayList<>(
                entriesOf(page).stream().map(row -> (String) row.get("id")).toList());

        Map<String, Object> next =
                trail("?entityType=" + PROBE + "&entityId=" + subject + "&limit=1&after=" + page.get("nextCursor"));
        seen.addAll(entriesOf(next).stream().map(row -> (String) row.get("id")).toList());

        assertThat(seen)
                .as("both rows, each exactly once")
                .containsExactlyInAnyOrder(first.toString(), second.toString());
    }

    @Test
    @DisplayName("a cursor this endpoint did not produce is refused rather than silently restarted")
    void aCorruptCursorIsABadRequest() {
        ResponseEntity<Map<String, Object>> refused = get(TRAIL + "?after=not-a-cursor", staffToken());

        // Serving the first page would make a client that is paging wrongly look like one
        // that has finished, and hand an investigator the top of the log in place of the
        // part they had not read.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "INVALID_CURSOR");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * One audit row, with both clocks set by hand.
     *
     * <p>Through SQL because {@code occurred_at} is {@code insertable = false}: the entity
     * cannot express a row whose timestamp disagrees with its key, which is the rule that
     * makes the column trustworthy and also the reason this test cannot go through it.
     *
     * @return the identifier, minted here so the caller can assert on the order
     */
    private UUID write(UUID entityId, String action, Instant occurredAt) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO audit_logs (id, occurred_at, actor_type, action, entity_type, entity_id, outcome)
                        VALUES (?, ?, 'SYSTEM', ?, ?, ?, 'SUCCEEDED')
                        """,
                        id,
                        java.sql.Timestamp.from(occurredAt),
                        action,
                        PROBE,
                        entityId);
        return id;
    }

    private Map<String, Object> trail(String query) {
        ResponseEntity<Map<String, Object>> response = get(TRAIL + query, staffToken());
        assertThat(response.getStatusCode())
                .as("reading the trail: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("entries");
    }

    /**
     * A minted token rather than a sign-in.
     *
     * <p>A dozen suites share this address and {@code sign-ins-per-email} is left at its
     * real value of five, so signing in here spends one of those five and fails somebody
     * else's suite with a 401 that has nothing to do with them.
     */
    private String staffToken() {
        EmailAddress email = EmailAddress.of(STAFF_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Moderator"),
                    String.class);
        }
        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();

        return tokens.issue(
                        id, UUID.randomUUID(), new AccessTokenIssuer.AccountStanding(true, false), false, Instant.now())
                .value();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        return rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
