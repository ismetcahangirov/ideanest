package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.project.infrastructure.CategoryRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queue that says what the three moderation outcomes apply to.
 *
 * <p>The gap this closes was not a missing convenience. {@code approve},
 * {@code reject} and {@code request-changes} have existed since #101 and nothing listed
 * their subjects, so the only way to reach a submitted campaign was a report somebody
 * had filed about it. {@link #aSubmittedCampaignAppearsWithoutAnybodyReportingIt()} is
 * that sentence as a test.
 *
 * <p>The two that carry the rest of the design are
 * {@link #theQueueIsOrderedBySubmissionRatherThanCreation()} — the reason the cursor is
 * the transition's identifier and not the campaign's — and
 * {@link #anUnreviewableStateIsRefusedRatherThanAnsweredEmpty()}.
 */
class SubmissionQueueApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    /** The address {@code application-test.yml} lists as staff. */
    private static final String STAFF_EMAIL = "moderator@ideanest.test";

    private static final String QUEUE = "/v1/admin/moderation/submissions";

    private static Account STAFF;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearProjects() {
        // Campaigns reference users and deliberately do not cascade from them, so rows
        // left here break the identity suites' own cleanup.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
    }

    // ------------------------------------------------------------------
    // The gap
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a submitted campaign appears in the queue without anybody reporting it")
    void aSubmittedCampaignAppearsWithoutAnybodyReportingIt() {
        Account creator = creator();
        UUID id = submitted(creator, "A campaign nobody complained about");

        Map<String, Object> body = queue(STAFF_EMAIL, "");

        assertThat(body).containsEntry("state", "SUBMITTED");
        assertThat(submissionsIn(body))
                .as("the campaign is reachable without a report existing about it")
                .anySatisfy(row -> assertThat(row).containsEntry("projectId", id.toString()));
    }

    @Test
    @DisplayName("a row carries what a moderator triages on")
    void aRowCarriesWhatAModeratorTriagesOn() {
        Account creator = creator();
        UUID id = submitted(creator, "A campaign with everything on it");

        Map<String, Object> row = rowFor(queue(STAFF_EMAIL, ""), id);

        assertThat(row).containsEntry("title", "A campaign with everything on it");
        assertThat(row).containsEntry("state", "SUBMITTED");
        assertThat(row).containsEntry("creatorId", creator.id().toString());
        assertThat(row).containsEntry("creatorName", "Test Creator");
        // Waiting since, because the queue is about how long somebody has been waiting
        // and about nothing the campaign contains.
        assertThat(row.get("waitingSince")).as("when it entered the state").isNotNull();
        assertThat(row.get("cursor")).as("its keyset position").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> goal = (Map<String, Object>) row.get("goal");
        // §10.3: money crosses as a string, never a number.
        assertThat(goal.get("amount")).isInstanceOf(String.class);
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the queue is ordered by submission rather than by creation")
    void theQueueIsOrderedBySubmissionRatherThanCreation() {
        Account creator = creator();

        // Created first, submitted second. A cursor taken off the campaign's own
        // identifier -- also UUIDv7, also sortable -- would put this one in front,
        // which on an oldest-first queue is how the longest-waiting campaign is the
        // one that gets skipped.
        UUID draftedFirst = idOf(submittableDraft(creator, "Drafted first"));
        UUID draftedSecond = idOf(submittableDraft(creator, "Drafted second"));

        post("/v1/projects/" + draftedSecond + "/submit", creator.accessToken(), null);
        post("/v1/projects/" + draftedFirst + "/submit", creator.accessToken(), null);

        List<String> order = submissionsIn(queue(STAFF_EMAIL, "")).stream()
                .map(row -> (String) row.get("projectId"))
                .toList();

        assertThat(order)
                .as("the campaign submitted first is the one waiting longest")
                .containsExactly(draftedSecond.toString(), draftedFirst.toString());
    }

    @Test
    @DisplayName("a resubmission goes to the back, dated from when it was resubmitted")
    void aResubmissionIsDatedFromTheResubmission() {
        Account creator = creator();
        UUID sentBack = idOf(submittableDraft(creator, "Sent back once"));

        post("/v1/projects/" + sentBack + "/submit", creator.accessToken(), null);
        Instant first = waitingSince(rowFor(queue(STAFF_EMAIL, ""), sentBack));

        post(
                "/v1/admin/moderation/" + sentBack + "/request-changes",
                STAFF.accessToken(),
                Map.of("note", "The risks section needs a date."));
        post("/v1/projects/" + sentBack + "/submit", creator.accessToken(), null);

        Instant second = waitingSince(rowFor(queue(STAFF_EMAIL, ""), sentBack));

        // The lateral join takes the latest transition INTO the current state, so the
        // clock restarts. A campaign sent back and returned a week later has not been
        // waiting on us for that week.
        assertThat(second)
                .as("the wait is measured from the resubmission, not the first submission")
                .isAfterOrEqualTo(first);
    }

    // ------------------------------------------------------------------
    // The states it serves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a decided campaign leaves the queue and is found under its outcome")
    void aDecidedCampaignMovesToItsOutcome() {
        Account creator = creator();
        UUID id = submitted(creator, "A campaign about to be sent back");

        post(
                "/v1/admin/moderation/" + id + "/request-changes",
                STAFF.accessToken(),
                Map.of("note", "Add a delivery estimate."));

        assertThat(submissionsIn(queue(STAFF_EMAIL, "")))
                .as("it is no longer waiting on anybody here")
                .noneSatisfy(row -> assertThat(row).containsEntry("projectId", id.toString()));

        Map<String, Object> decided = rowFor(queue(STAFF_EMAIL, "?state=CHANGES_REQUESTED"), id);
        assertThat(decided).containsEntry("state", "CHANGES_REQUESTED");
        // The note rides with the row, so a moderator revisiting a decision reads what
        // was said without opening the campaign.
        assertThat(decided).containsEntry("note", "Add a delivery estimate.");
    }

    @Test
    @DisplayName("an unreviewable state is refused rather than answered empty")
    void anUnreviewableStateIsRefusedRatherThanAnsweredEmpty() {
        ResponseEntity<Map<String, Object>> refused = get(QUEUE + "?state=LIVE", STAFF.accessToken());

        // An empty page here reads as "there is nothing to review", which is a claim
        // about the platform rather than about the question that was asked.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "UNREVIEWABLE_STATE");
        assertThat(refused.getBody()).containsEntry("state", "LIVE");
    }

    // ------------------------------------------------------------------
    // Who may read it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the queue refuses an account that is not platform staff")
    void theQueueRefusesAnAccountThatIsNotStaff() {
        Account stranger = creator();

        ResponseEntity<Map<String, Object>> refused = get(QUEUE, stranger.accessToken());

        // Listing every unlaunched campaign, its goal and the fact its creator is
        // waiting on us, to anybody with an account, is the failure this check exists
        // to prevent -- and a creator seeing a rival's unlaunched campaign is the
        // cheapest way to reach it.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the queue refuses a request with no credentials at all")
    void theQueueRefusesAnAnonymousRequest() {
        ResponseEntity<Map<String, Object>> refused = get(QUEUE, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Paging
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a full page carries a cursor and the next page continues from it")
    void pagingWalksTheQueueWithoutRepeatingOrSkipping() {
        Account creator = creator();
        UUID first = submitted(creator, "First in");
        UUID second = submitted(creator, "Second in");
        UUID third = submitted(creator, "Third in");

        Map<String, Object> page = queue(STAFF_EMAIL, "?limit=2");
        List<String> seen = submissionsIn(page).stream()
                .map(row -> (String) row.get("projectId"))
                .toList();

        assertThat(seen).containsExactly(first.toString(), second.toString());
        assertThat(page.get("nextCursor")).as("a full page may have more behind it").isNotNull();

        Map<String, Object> next = queue(STAFF_EMAIL, "?limit=2&after=" + page.get("nextCursor"));
        assertThat(submissionsIn(next).stream().map(row -> row.get("projectId")).toList())
                .as("the second page continues rather than repeating")
                .containsExactly(third.toString());
        assertThat(next.get("nextCursor"))
                .as("a short page is the end, and says so without costing a request to find out")
                .isNull();
    }

    @Test
    @DisplayName("an empty queue is an empty list rather than a failure")
    void anEmptyQueueIsAnEmptyList() {
        Map<String, Object> body = queue(STAFF_EMAIL, "");

        assertThat(submissionsIn(body)).isEmpty();
        assertThat(body.get("nextCursor")).isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(String accessToken, UUID id) {
    }

    private Account creator() {
        // Its own prefix: two suites sharing one handle sign in as each other's
        // accounts and fail three frames from the cause.
        EmailAddress email = EmailAddress.of("submission-queue" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        // Submitting needs a plan since #368.
        Campaigns.mayPublish(dataSource, id);
        return new Account((String) signedIn.getBody().get("accessToken"), id);
    }

    /**
     * The staff account, with a minted token rather than a sign-in.
     *
     * <p>A dozen suites share this address and {@code sign-ins-per-email} is left at
     * its real value of five, so signing in here spends one of those five and fails
     * somebody else's suite with a 401 that has nothing to do with them.
     */
    private Account staff() {
        if (STAFF != null) {
            return STAFF;
        }
        EmailAddress email = EmailAddress.of(STAFF_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Moderator"),
                    String.class);
        }

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String accessToken = tokens.issue(
                        id, UUID.randomUUID(), new AccessTokenIssuer.AccountStanding(true, false), false, Instant.now())
                .value();

        STAFF = new Account(accessToken, id);
        return STAFF;
    }

    /**
     * A draft §5.3 is satisfied with.
     *
     * <p>Created with a title and then completed by a patch, which is the only order
     * that works: {@code POST /v1/projects} takes the title and ignores the rest, so a
     * fixture that posted everything at once produces a draft the checklist refuses --
     * and the refusal surfaces three assertions later as an empty queue.
     */
    private Map<String, Object> submittableDraft(Account creator, String title) {
        Map<String, Object> draft = post("/v1/projects", creator.accessToken(), Map.of("title", title))
                .getBody();
        return patch("/v1/projects/" + idOf(draft), creator.accessToken(), Campaigns.completeBasics(categories))
                .getBody();
    }

    private UUID submitted(Account creator, String title) {
        UUID id = idOf(submittableDraft(creator, title));
        ResponseEntity<Map<String, Object>> submitted =
                post("/v1/projects/" + id + "/submit", creator.accessToken(), null);

        // Asserted rather than assumed. A refused submission leaves the campaign in
        // DRAFT and every assertion downstream reads as "the queue is broken".
        assertThat(submitted.getStatusCode()).as("submitting %s: %s", title, submitted.getBody()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private static UUID idOf(Map<String, Object> project) {
        return UUID.fromString((String) project.get("id"));
    }

    private static Instant waitingSince(Map<String, Object> row) {
        return Instant.parse((String) row.get("waitingSince"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> submissionsIn(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("submissions");
    }

    private static Map<String, Object> rowFor(Map<String, Object> body, UUID projectId) {
        return submissionsIn(body).stream()
                .filter(row -> projectId.toString().equals(row.get("projectId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + projectId + " in " + body));
    }

    /** The queue as staff sees it. Takes the address rather than the token so that {@link #staff()} is lazy. */
    private Map<String, Object> queue(String staffEmail, String query) {
        staff();
        ResponseEntity<Map<String, Object>> response = get(QUEUE + query, STAFF.accessToken());
        assertThat(response.getStatusCode()).as("reading the queue as %s", staffEmail).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String accessToken) {
        return rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> patch(String path, String accessToken, Object body) {
        return rest.exchange(
                path,
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> post(String path, String accessToken, Object body) {
        return rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private static HttpHeaders headers(String accessToken) {
        HttpHeaders headers = jsonHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return headers;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
