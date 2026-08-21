package az.ideanest.community;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
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
 * §4.9's C-09 and C-10 over HTTP: saving a campaign, following an account, and the two lists.
 *
 * <p>The tests that carry the design:
 *
 * <ul>
 *   <li>{@link #savingTwiceIsOneRowAndOneAnswer()} — idempotency is the whole promise of a
 *       toggle, and it belongs to the unique constraint rather than to a check in Java. Without
 *       it two taps on a slow connection are two rows.
 *   <li>{@link #aCampaignTheCallerMayNotSeeIsNotFound()} — a draft is an unreleased product, so
 *       saving is not a way to find out that one exists.
 *   <li>{@link #unSavingWorksOnACampaignThatIsNoLongerPublic()} — the one place the visibility
 *       check is deliberately absent, and it matters: a campaign that stopped being public is
 *       the one somebody most wants off their list.
 *   <li>{@link #theSavedListPagesWithoutSkippingOrRepeating()} — the cursor is two columns
 *       because one is not enough, and rows created in the same instant are the case that
 *       proves it.
 *   <li>{@link #followingYourselfIsRefused()} — a self-follow would put a creator in their own
 *       {@code FOLLOWERS} audience, and launching would then notify them that somebody they
 *       follow had launched.
 * </ul>
 */
class BackerSignalApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM saves");
        jdbc.update("DELETE FROM follows");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saving a campaign records it and reports the resulting state")
    void savingACampaignRecordsIt() {
        Account creator = account("saver-creator");
        Account reader = account("saver-reader");
        UUID project = liveCampaign(creator);

        ResponseEntity<Map<String, Object>> saved = save(project, reader);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).containsEntry("saved", true);
        assertThat(saveCount(project, reader.id())).isEqualTo(1);
    }

    /**
     * Idempotency, which is the database's rather than the service's.
     *
     * <p>{@code saves_one_per_account} is the check and {@code ON CONFLICT DO NOTHING} is how it
     * gets to be it. A read-then-write in Java loses the race between two taps — both see no
     * row, both insert — and the response would then have to report which call won, which is a
     * distinction the caller cannot observe and should not be asked to handle.
     */
    @Test
    @DisplayName("saving twice is one row and the same answer")
    void savingTwiceIsOneRowAndOneAnswer() {
        Account creator = account("twice-creator");
        Account reader = account("twice-reader");
        UUID project = liveCampaign(creator);

        save(project, reader);
        ResponseEntity<Map<String, Object>> again = save(project, reader);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("saved", true);
        assertThat(saveCount(project, reader.id())).as("one row, not two").isEqualTo(1);
    }

    @Test
    @DisplayName("un-saving removes the row, and un-saving again is still success")
    void unSavingIsIdempotent() {
        Account creator = account("unsave-creator");
        Account reader = account("unsave-reader");
        UUID project = liveCampaign(creator);

        save(project, reader);
        ResponseEntity<Map<String, Object>> removed = unsave(project, reader);
        ResponseEntity<Map<String, Object>> again = unsave(project, reader);

        assertThat(removed.getBody()).containsEntry("saved", false);
        assertThat(again.getStatusCode()).as("nothing to remove is the state the caller asked for").isEqualTo(HttpStatus.OK);
        assertThat(saveCount(project, reader.id())).isZero();
    }

    /**
     * A draft is confidential, and saving must not be a way to discover one.
     *
     * <p>The same 404 a stranger gets for a campaign that does not exist — {@code CommentService}
     * states the rule and this endpoint inherits it through {@code PublicProjects}.
     */
    @Test
    @DisplayName("a campaign the caller may not see is not found")
    void aCampaignTheCallerMayNotSeeIsNotFound() {
        Account creator = account("draft-creator");
        Account stranger = account("draft-stranger");
        UUID draft = draft(creator);

        ResponseEntity<Map<String, Object>> refused = save(draft, stranger);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody()).containsEntry("code", "PROJECT_NOT_FOUND");
    }

    @Test
    @DisplayName("a campaign that does not exist is not found")
    void anUnknownCampaignIsNotFound() {
        Account reader = account("unknown-reader");

        assertThat(save(UUID.randomUUID(), reader).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The one place the visibility check is deliberately absent.
     *
     * <p>{@code BackerSignalService#unsave} argues it: a campaign that has stopped being public
     * is exactly the one somebody is most likely to want off their list, and refusing would
     * leave a row only an administrator could remove.
     */
    @Test
    @DisplayName("un-saving works on a campaign that is no longer public")
    void unSavingWorksOnACampaignThatIsNoLongerPublic() {
        Account creator = account("gone-creator");
        Account reader = account("gone-reader");
        UUID project = liveCampaign(creator);
        save(project, reader);

        // Back to a state §6.1 does not publish. The saved row stays, deliberately -- removing
        // it would be deciding on the reader's behalf that they are no longer interested.
        new JdbcTemplate(dataSource).update("UPDATE projects SET state = 'DRAFT' WHERE id = ?", project);

        assertThat(saveCount(project, reader.id())).as("the save survives the campaign going private").isEqualTo(1);
        ResponseEntity<Map<String, Object>> removed = unsave(project, reader);

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saveCount(project, reader.id())).isZero();
    }

    // ------------------------------------------------------------------
    // The saved list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the saved list names the campaign and its public path, newest first")
    void theSavedListNamesTheCampaign() {
        Account creator = account("list-creator");
        Account reader = account("list-reader");
        UUID first = liveCampaign(creator);
        UUID second = liveCampaign(creator);

        save(first, reader);
        save(second, reader);

        Map<String, Object> page = get("/v1/me/saved", reader).getBody();
        List<Map<String, Object>> items = items(page);

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("projectId", second.toString());
        assertThat(items.get(0).get("title")).isNotNull();
        assertThat(items.get(0).get("creatorSlug")).as("both halves of the public path").isNotNull();
        assertThat(items.get(0).get("projectSlug")).isNotNull();
        assertThat(page.get("nextCursor")).as("one page is the whole list").isNull();
    }

    /**
     * Keyset paging over rows that can share an instant.
     *
     * <p>The cursor is {@code (createdAt, id)} because a cursor on the timestamp alone would
     * either skip a row or repeat one when two saves land in the same instant — which is what
     * two taps in a row produce. Asserted by walking the whole list one row at a time and
     * requiring that what comes back is exactly the set that went in.
     */
    @Test
    @DisplayName("the saved list pages without skipping or repeating")
    void theSavedListPagesWithoutSkippingOrRepeating() {
        Account creator = account("page-creator");
        Account reader = account("page-reader");

        List<UUID> projects = List.of(
                liveCampaign(creator), liveCampaign(creator), liveCampaign(creator), liveCampaign(creator));
        projects.forEach(project -> save(project, reader));

        List<String> walked = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < projects.size(); page++) {
            String path = "/v1/me/saved?size=1" + (cursor == null ? "" : "&cursor=" + cursor);
            Map<String, Object> body = get(path, reader).getBody();
            List<Map<String, Object>> items = items(body);

            assertThat(items).hasSize(1);
            walked.add((String) items.get(0).get("projectId"));
            cursor = (String) body.get("nextCursor");
            if (cursor == null) {
                break;
            }
        }

        assertThat(walked)
                .as("every saved campaign exactly once")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(projects.stream().map(UUID::toString).toList());
        assertThat(cursor).as("the last page hands out no cursor").isNull();
    }

    @Test
    @DisplayName("a cursor this endpoint did not issue is refused rather than ignored")
    void aForgedCursorIsRefused() {
        Account reader = account("cursor-reader");

        ResponseEntity<Map<String, Object>> refused = get("/v1/me/saved?cursor=not-a-cursor", reader);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "INVALID_CURSOR");
        assertThat(refused.getBody().get("detail"))
                .as("the body does not echo what the caller sent")
                .asString()
                .doesNotContain("not-a-cursor");
    }

    @Test
    @DisplayName("one account's saved list is not another's")
    void aSavedListIsPrivateToItsAccount() {
        Account creator = account("private-creator");
        Account reader = account("private-reader");
        Account other = account("private-other");
        UUID project = liveCampaign(creator);
        save(project, reader);

        assertThat(items(get("/v1/me/saved", other).getBody())).isEmpty();
        assertThat(items(get("/v1/me/saved", reader).getBody())).hasSize(1);
    }

    @Test
    @DisplayName("saving requires a bearer token")
    void savingRequiresACaller() {
        Account creator = account("anon-creator");
        UUID project = liveCampaign(creator);

        ResponseEntity<Map<String, Object>> refused = exchange(
                "/v1/projects/" + project + "/save", HttpMethod.POST, null, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Following
    // ------------------------------------------------------------------

    @Test
    @DisplayName("following an account records it and shows up in the following list")
    void followingAnAccountRecordsIt() {
        Account creator = account("follow-creator");
        Account follower = account("follow-follower");
        String slug = slugOf(creator);

        ResponseEntity<Map<String, Object>> followed = follow(slug, follower);

        assertThat(followed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(followed.getBody()).containsEntry("following", true);

        List<Map<String, Object>> items = items(get("/v1/me/following", follower).getBody());
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("creatorId", creator.id().toString());
        assertThat(items.get(0)).containsEntry("slug", slug);
        assertThat(items.get(0)).as("§17.4: a list of other people carries no address").doesNotContainKey("email");
    }

    @Test
    @DisplayName("following twice is one row")
    void followingTwiceIsOneRow() {
        Account creator = account("dupe-creator");
        Account follower = account("dupe-follower");
        String slug = slugOf(creator);

        follow(slug, follower);
        follow(slug, follower);

        assertThat(followCount(creator.id(), follower.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("unfollowing removes the row, and unfollowing again is still success")
    void unfollowingIsIdempotent() {
        Account creator = account("unfollow-creator");
        Account follower = account("unfollow-follower");
        String slug = slugOf(creator);

        follow(slug, follower);
        assertThat(unfollow(slug, follower).getBody()).containsEntry("following", false);
        assertThat(unfollow(slug, follower).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(followCount(creator.id(), follower.id())).isZero();
    }

    /**
     * A self-follow is refused with a sentence rather than a constraint violation.
     *
     * <p>{@code follows_is_not_self} would refuse the row regardless. What this asserts is that
     * the refusal arrives as a 422 naming the problem instead of a 500 — and the reason it is
     * refused at all is on {@code ProjectAudience.FOLLOWERS}: a creator in their own followers
     * audience would be told that somebody they follow had launched their own campaign.
     */
    @Test
    @DisplayName("following yourself is refused")
    void followingYourselfIsRefused() {
        Account self = account("self-follower");

        ResponseEntity<Map<String, Object>> refused = follow(slugOf(self), self);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody()).containsEntry("code", "CANNOT_FOLLOW_YOURSELF");
        assertThat(followCount(self.id(), self.id())).isZero();
    }

    @Test
    @DisplayName("an account that does not exist is not found")
    void anUnknownAccountIsNotFound() {
        Account follower = account("nobody-follower");

        ResponseEntity<Map<String, Object>> refused = follow("no-such-person-at-all", follower);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody()).containsEntry("code", "USER_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(String accessToken, UUID id) {
    }

    private Account account(String prefix) {
        EmailAddress email = EmailAddress.of(prefix + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);

        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account((String) signedIn.getBody().get("accessToken"), id);
    }

    private String slugOf(Account account) {
        return users.findByIdAndDeletedAtIsNull(account.id()).orElseThrow().getSlug();
    }

    private UUID draft(Account creator) {
        ResponseEntity<Map<String, Object>> created = exchange(
                "/v1/projects",
                HttpMethod.POST,
                creator.accessToken(),
                Map.of("title", "A campaign worth saving " + SEQUENCE.incrementAndGet()));
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private UUID liveCampaign(Account creator) {
        UUID project = draft(creator);
        Campaigns.launch(dataSource, project);
        return project;
    }

    private ResponseEntity<Map<String, Object>> save(UUID project, Account caller) {
        return exchange("/v1/projects/" + project + "/save", HttpMethod.POST, caller.accessToken(), null);
    }

    private ResponseEntity<Map<String, Object>> unsave(UUID project, Account caller) {
        return exchange("/v1/projects/" + project + "/save", HttpMethod.DELETE, caller.accessToken(), null);
    }

    private ResponseEntity<Map<String, Object>> follow(String slug, Account caller) {
        return exchange("/v1/users/" + slug + "/follow", HttpMethod.POST, caller.accessToken(), null);
    }

    private ResponseEntity<Map<String, Object>> unfollow(String slug, Account caller) {
        return exchange("/v1/users/" + slug + "/follow", HttpMethod.DELETE, caller.accessToken(), null);
    }

    private ResponseEntity<Map<String, Object>> get(String path, Account caller) {
        return exchange(path, HttpMethod.GET, caller.accessToken(), null);
    }

    private ResponseEntity<Map<String, Object>> exchange(String path, HttpMethod method, String token, Object body) {
        return rest.exchange(
                path,
                method,
                new HttpEntity<>(body, token == null ? jsonHeaders() : bearer(token)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private long saveCount(UUID project, UUID account) {
        return new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM saves WHERE project_id = ? AND user_id = ?",
                        Long.class,
                        project,
                        account);
    }

    private long followCount(UUID creator, UUID follower) {
        return new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM follows WHERE creator_id = ? AND follower_id = ?",
                        Long.class,
                        creator,
                        follower);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
