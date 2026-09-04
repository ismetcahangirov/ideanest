package az.ideanest.admin;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The account directory folds like every other search, and can be served from an index —
 * issue #413.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The platform has one definition of how a word is folded for comparison — §11.3, written
 * as {@code Slugs.fold} in Java and {@code ideanest_fold(text)} in the database (V13), pinned
 * to each other by {@code SearchFoldingTests}. Public search uses it, the campaign directory
 * uses it, and V63 built {@code users_name_trgm_idx} and {@code users_slug_trgm_idx} on it.
 *
 * <p>{@code /admin/users} did not. Its query is JPQL and had matched on {@code lower()} since
 * #104, because JPQL may only call a function the dialect knows about and nothing had
 * registered this one. Two consequences, and this suite pins both:
 *
 * <ul>
 *   <li>{@code lower()} leaves {@code ə}, {@code ı}, {@code ö}, {@code ü}, {@code ğ},
 *       {@code ş} and {@code ç} alone, so the account directory found "Köhnə" from
 *       {@code köhnə} and not from {@code kohne} — while the campaign directory beside it,
 *       searching the same table for the same person, found both. A console with two
 *       spellings of one rule is a console where staff learn which box needs the right
 *       keyboard.
 *   <li>An expression index serves a query that repeats the expression exactly, and
 *       {@code lower(name)} is not {@code ideanest_fold(name)}, so V63's two indexes could
 *       not be used by this read no matter how large the table grew.
 * </ul>
 *
 * <h2>What the second test does and does not claim</h2>
 *
 * <p>{@link #theFoldedPredicatesCanBeServedFromAnIndex()} runs {@code EXPLAIN} with
 * {@code enable_seqscan} off. That is deliberate and the reason is worth stating rather than
 * discovering: on nine hundred accounts PostgreSQL will read the table sequentially whatever
 * indexes exist, because it is cheaper, and asserting on the plan it picks at seed scale would
 * pin the size of the fixture rather than the shape of the query.
 *
 * <p>What it does assert is the thing that was actually broken and that no amount of data
 * would have fixed: that an index exists which <em>can</em> serve these three predicates. Under
 * {@code lower()} the planner had no such choice — with sequential scans disabled it would
 * still have had to read every row — so this fails before the change and passes after it, which
 * is what the assertion is for.
 */
class AccountDirectorySearchApiTests extends AbstractIntegrationTest {

    /** The address {@code application-test.yml} lists as staff. */
    private static final String STAFF_EMAIL = "moderator@ideanest.test";

    private static final String PASSWORD = "a-long-enough-password";

    private static final String DIRECTORY = "/v1/admin/users";

    /** Keeps two accounts in one run from colliding on a handle. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("searching for kohne finds an account named Köhnə")
    void anAsciiTermFindsAFoldedName() {
        String handle = "fold-name-" + SEQUENCE.incrementAndGet();
        UUID account = Campaigns.creator(dataSource, handle, "Köhnə Şəhər");

        // The acceptance criterion, and the one a member of staff would notice: an operator
        // holding a complaint types what is on their keyboard.
        assertThat(idsOf(search("kohne"))).contains(account.toString());
    }

    @Test
    @DisplayName("the same term still finds the name spelled the way it is written")
    void theFoldedFormAndTheWrittenFormFindTheSameAccount() {
        String handle = "fold-both-" + SEQUENCE.incrementAndGet();
        UUID account = Campaigns.creator(dataSource, handle, "Köhnə Şəhər");

        // Folding the term is not a trade: the column is folded too, so both spellings arrive
        // at the same value. A fix that made `kohne` work and `köhnə` stop would have moved
        // the defect rather than removed it.
        assertThat(idsOf(search("köhnə"))).contains(account.toString());
        assertThat(idsOf(search("KÖHNƏ"))).as("and the case of it").contains(account.toString());
    }

    @Test
    @DisplayName("a folded term matches a profile path and an address, not only a name")
    void theOtherTwoColumnsAreFoldedToo() {
        String handle = "fold-slug-" + SEQUENCE.incrementAndGet();
        UUID account = Campaigns.creator(dataSource, handle, "Someone Else");

        // Staff arrive holding whatever the complaint gave them. All three columns are matched
        // through the same fold now, which is what stops one box having two rules in it.
        assertThat(idsOf(search(handle))).as("by profile path").contains(account.toString());
        assertThat(idsOf(search(handle + "@example.com")))
                .as("by address")
                .contains(account.toString());
    }

    @Test
    @DisplayName("a wildcard a reader typed is still a literal, not a match on everybody")
    void typedWildcardsAreEscaped() {
        String handle = "fold-escape-" + SEQUENCE.incrementAndGet();
        Campaigns.creator(dataSource, handle, "Nothing Special");

        // `patternOf` escapes what the caller typed before wrapping it. Folding moved that
        // escaping behind a different function and it would be easy to lose on the way.
        assertThat(idsOf(search("%"))).as("a lone wildcard matches nobody").isEmpty();
    }

    @Test
    @DisplayName("an index exists that can serve the folded predicates")
    void theFoldedPredicatesCanBeServedFromAnIndex() {
        /*
         * One statement, because the read is one statement: the three predicates are an `OR`,
         * and a disjunction is only as indexed as its least indexed branch. Before V64 the
         * address had no trigram index, which would have left all three behind a sequential
         * scan and made V63's two decorative -- so asserting on them one at a time would have
         * passed while the query that matters could not use any of them.
         *
         * The predicates are written here exactly as `UserRepository.search` names them, which
         * is the property under test: an expression index serves a query that repeats the
         * expression, and `lower(name)` is not `ideanest_fold(name)`.
         */
        String plan = String.join("\n", explainWithoutSequentialScans("""
                EXPLAIN
                SELECT u.id FROM users u
                WHERE u.deleted_at IS NULL
                  AND (ideanest_fold(u.email) LIKE '%kohne%' ESCAPE '!'
                       OR ideanest_fold(u.name) LIKE '%kohne%' ESCAPE '!'
                       OR ideanest_fold(u.slug) LIKE '%kohne%' ESCAPE '!')
                """));

        assertThat(plan)
                .as("the plan, with sequential scans priced out: %s", plan)
                .contains("users_name_trgm_idx")
                .contains("users_slug_trgm_idx")
                .contains("users_email_trgm_idx");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A plan for one statement, with sequential scans priced out.
     *
     * <p>Both statements go to one connection, because a planner setting applies to the session
     * that made it and {@link JdbcTemplate} is free to hand each call a different one. Reset in
     * a {@code finally} rather than left to the pool: a connection returned with
     * {@code enable_seqscan} off would quietly change the plan of every query in every suite
     * that borrowed it next, which is the kind of failure nobody attributes to this file.
     */
    private List<String> explainWithoutSequentialScans(String statement) {
        return new JdbcTemplate(dataSource).execute((ConnectionCallback<List<String>>) connection -> {
            try (Statement session = connection.createStatement()) {
                session.execute("SET enable_seqscan = off");
                try (ResultSet rows = session.executeQuery(statement)) {
                    List<String> lines = new ArrayList<>();
                    while (rows.next()) {
                        lines.add(rows.getString(1));
                    }
                    return lines;
                } finally {
                    session.execute("RESET enable_seqscan");
                }
            }
        });
    }

    /**
     * One search, as the console makes it.
     *
     * <p>The term is a URI variable rather than a value pasted into the path, so that
     * {@code RestTemplate} encodes it once and correctly. It matters here more than it usually
     * does: two of these terms are non-ASCII — which is the whole subject of this suite — and
     * one of them is a bare {@code %}, which pasted into a template is either a malformed
     * escape or a double-encoded one depending on who parses it first.
     */
    private List<Map<String, Object>> search(String term) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(staffToken());

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                DIRECTORY + "?query={term}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {},
                term);

        assertThat(response.getStatusCode())
                .as("searching the directory: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return usersOf(response.getBody());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> usersOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("users");
    }

    private static List<String> idsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (String) row.get("id")).toList();
    }

    /**
     * A minted token rather than a sign-in.
     *
     * <p>A dozen suites share this address and {@code sign-ins-per-email} is left at its real
     * value of five, so signing in here spends one of those five and fails somebody else's suite
     * with a 401 that has nothing to do with them.
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
}
