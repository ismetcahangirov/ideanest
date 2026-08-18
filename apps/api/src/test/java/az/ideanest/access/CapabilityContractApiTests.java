package az.ideanest.access;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditEntryRepository;
import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.RecordingCollaboratorInvitationNotifier;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * The capability contract of #236, at the four surfaces it was built for.
 *
 * <p><strong>This file is the defect, written down.</strong> Before the contract
 * existed, four modules wanted a fine-grained permission check, could not name
 * {@code project.domain.Capability} across the module boundary, and each settled for
 * the coarsest question the project module published — "may this account edit this
 * campaign at all". The result was that one grant did the work of four:
 * {@link #onlyEditingRewardsDoesNotPublishAnUpdate()} and
 * {@link #onlyEditingRewardsDoesNotReadTheReferralReport()} are the two requests a
 * collaborator invited to price reward tiers could make and should never have been
 * able to.
 *
 * <p>Each capability therefore appears twice: once refused to a collaborator who
 * holds a different one, and once allowed to the collaborator who holds it. A test
 * that only asserted the refusal would pass just as well against an endpoint that
 * refused everybody.
 *
 * <p><strong>Over HTTP and against the real database</strong>, because the thing being
 * checked is the whole path: a grant written by the invitation endpoint, read back by
 * {@code ProjectAccess} through {@code collaborator_capabilities}, and turned into a
 * status code by each module's own exception handler. A unit test of the contract
 * ({@code ProjectCapabilityContractTests}) proves the vocabulary lines up; only this
 * proves the wiring does.
 */
class CapabilityContractApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PASSWORD = "a-long-enough-password";

    /** The single address {@code application-test.yml} lists as platform staff. */
    private static final String MODERATOR_EMAIL = "moderator@ideanest.test";

    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT =
            new ParameterizedTypeReference<Map<String, Object>>() {};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private RecordingCollaboratorInvitationNotifier invitations;

    @Autowired
    private AuditEntryRepository auditEntries;

    /** See {@link #staff()}: a token is minted rather than signed in for. */
    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        // Updates and grants cascade from the campaign; removed explicitly anyway,
        // because a cleanup that leans on a cascade stops working the day the cascade
        // is reconsidered and nothing says why. Audit rows are left where they are:
        // audit_logs refuses DELETE, which is the property it exists for.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM project_updates");
        jdbc.update("DELETE FROM collaborators");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
        invitations.clear();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Account(EmailAddress email, String accessToken, UUID id) {
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
                OBJECT);

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account(email, (String) signedIn.getBody().get("accessToken"), id);
    }

    /**
     * The one account this configuration treats as platform staff.
     *
     * <p>Its token is minted rather than signed in for, for the reason
     * {@code ContentReportApiTests} sets out at length: several suites share the single
     * configured moderator address, {@code sign-ins-per-email} is deliberately left at
     * its real value, and one more suite signing in as it would spend somebody else's
     * budget and fail their tests instead of its own.
     */
    private Account staff() {
        EmailAddress email = EmailAddress.of(MODERATOR_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Moderator"),
                    String.class);
        }

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String accessToken = tokens.issue(
                        id,
                        UUID.randomUUID(),
                        new AccessTokenIssuer.AccountStanding(true, false),
                        false,
                        Instant.now())
                .value();
        return new Account(email, accessToken, id);
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

    private ResponseEntity<Map<String, Object>> post(String path, String accessToken, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(accessToken)), OBJECT);
    }

    /**
     * A GET whose body is read as a string.
     *
     * <p>The reward list answers a JSON array and every refusal answers a problem
     * detail, so asking Jackson for one shape makes the other a parse error reported in
     * place of the status this file is about.
     */
    private ResponseEntity<String> getRaw(String path, String accessToken) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), String.class);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String accessToken) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), OBJECT);
    }

    private UUID draft(Account creator) {
        Map<String, Object> project = post("/v1/projects", creator.accessToken(), Map.of("title", "A campaign"))
                .getBody();
        return UUID.fromString((String) project.get("id"));
    }

    /** An accepted grant conferring exactly the capabilities named. */
    private Account collaborator(UUID projectId, Account creator, String... capabilities) {
        Account invitee = account("collaborator");
        ResponseEntity<Map<String, Object>> invited = post(
                "/v1/projects/" + projectId + "/collaborators",
                creator.accessToken(),
                Map.of("email", invitee.email().value(), "capabilities", List.of(capabilities)));
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String token = invitations.tokenSentTo(invitee.email()).orElseThrow();
        assertThat(post("/v1/collaborators/invitations/" + token + "/accept", invitee.accessToken(), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return invitee;
    }

    private static Map<String, Object> anUpdate() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "The moulds arrived");
        body.put("body", "The first run starts on Monday and the tooling is signed off.");
        body.put("visibility", "PUBLIC");
        return body;
    }

    private static Map<String, Object> aRewardTier() {
        return Map.of("title", "An early copy", "price", Map.of("amount", "25.00", "currency", "AZN"));
    }

    private int countIn(String table, UUID projectId) {
        return new JdbcTemplate(dataSource)
                .queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?", Integer.class, projectId);
    }

    // ------------------------------------------------------------------
    // community — PUBLISH_UPDATES
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collaborator granted only EDIT_REWARDS may not publish a project update")
    void onlyEditingRewardsDoesNotPublishAnUpdate() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);
        Account pricer = collaborator(project, creator, "EDIT_REWARDS");

        ResponseEntity<Map<String, Object>> refused =
                post("/v1/projects/" + project + "/updates", pricer.accessToken(), anUpdate());

        // 403 rather than 404: they were invited, they can already see the campaign,
        // and there is nothing left to hide from them.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "CAPABILITY_NOT_GRANTED");

        // And nothing was written. A refusal that still left a row would be the same
        // defect with a quieter symptom.
        assertThat(countIn("project_updates", project)).isZero();
    }

    @Test
    @DisplayName("a collaborator granted PUBLISH_UPDATES publishes one, and the audit row says who did")
    void publishUpdatesPublishesAnUpdate() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);
        Account announcer = collaborator(project, creator, "PUBLISH_UPDATES");

        ResponseEntity<Map<String, Object>> published =
                post("/v1/projects/" + project + "/updates", announcer.accessToken(), anUpdate());

        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(published.getBody()).containsEntry("number", 1);

        // §5.5's obligation is still audited under the narrower check. Losing the
        // audit row while tightening the permission would trade one defect for a
        // quieter one.
        var recorded = auditEntries.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                AuditAction.PROJECT_UPDATE_PUBLISHED.entityType(), project);
        assertThat(recorded).isNotEmpty();
        assertThat(recorded.getFirst().getActorId()).isEqualTo(announcer.id());
        assertThat(recorded.getFirst().getDetail()).contains("number=1");
    }

    // ------------------------------------------------------------------
    // analytics — VIEW_FINANCES
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collaborator granted only EDIT_REWARDS may not read the referral report")
    void onlyEditingRewardsDoesNotReadTheReferralReport() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);
        Account pricer = collaborator(project, creator, "EDIT_REWARDS");

        ResponseEntity<String> refused = getRaw("/v1/projects/" + project + "/referrers", pricer.accessToken());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("CAPABILITY_NOT_GRANTED");
    }

    @Test
    @DisplayName("a collaborator granted VIEW_FINANCES reads the referral report")
    void viewFinancesReadsTheReferralReport() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);
        Account analyst = collaborator(project, creator, "VIEW_FINANCES");

        ResponseEntity<Map<String, Object>> report =
                get("/v1/projects/" + project + "/referrers", analyst.accessToken());

        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A real report body rather than an empty one, so that "allowed" means the
        // report was served and not merely that nothing refused it.
        assertThat(((Number) report.getBody().get("pledgeCount")).longValue()).isZero();
        assertThat((List<?>) report.getBody().get("sources")).isEmpty();
        // No attributions and therefore no currency and no total. Absent, not null:
        // ReferrerReportResponse is NON_NULL, so "this campaign has taken nothing yet"
        // is said by omitting the fields — the shape ReferralApiTests already fixes.
        assertThat(report.getBody()).doesNotContainKey("currency").doesNotContainKey("value");
    }

    // ------------------------------------------------------------------
    // reward — EDIT_REWARDS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collaborator granted only EDIT_STORY may not read or price reward tiers")
    void onlyEditingTheStoryDoesNotTouchRewards() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);
        Account writer = collaborator(project, creator, "EDIT_STORY");

        assertThat(getRaw("/v1/projects/" + project + "/rewards", writer.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map<String, Object>> refused =
                post("/v1/projects/" + project + "/rewards", writer.accessToken(), aRewardTier());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "CAPABILITY_NOT_GRANTED");
    }

    @Test
    @DisplayName("a collaborator granted EDIT_REWARDS prices a tier")
    void editRewardsPricesATier() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);
        Account pricer = collaborator(project, creator, "EDIT_REWARDS");

        ResponseEntity<Map<String, Object>> created =
                post("/v1/projects/" + project + "/rewards", pricer.accessToken(), aRewardTier());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(getRaw("/v1/projects/" + project + "/rewards", pricer.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // moderation — staff identity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the report queue refuses an account that is not platform staff")
    void theReportQueueRefusesAnAccountThatIsNotStaff() {
        Account ordinary = account("contract-ordinary");

        ResponseEntity<String> refused = getRaw("/v1/admin/moderation/reports", ordinary.accessToken());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("NOT_A_MODERATOR");
    }

    @Test
    @DisplayName("the report queue admits platform staff, asked through the contract")
    void theReportQueueAdmitsStaff() {
        assertThat(getRaw("/v1/admin/moderation/reports", staff().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // The creator, and the stranger
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the creator still does all three things, holding no grant row at all")
    void theCreatorHoldsEveryCapabilityImplicitly() {
        Account creator = account("contract-creator");
        UUID project = draft(creator);

        // The narrower checks are a tightening for collaborators and must be no
        // change at all for the person who owns the campaign — whose authority comes
        // from projects.creator_id and not from collaborator_capabilities.
        assertThat(post("/v1/projects/" + project + "/updates", creator.accessToken(), anUpdate())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/v1/projects/" + project + "/rewards", creator.accessToken(), aRewardTier())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(get("/v1/projects/" + project + "/referrers", creator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(countIn("collaborators", project)).isZero();
    }

    @Test
    @DisplayName("a stranger is told the campaign does not exist, whichever capability the request needed")
    void aStrangerIsToldNothing() {
        Account creator = account("contract-creator");
        Account stranger = account("contract-stranger");
        UUID project = draft(creator);

        // The refusal a caller with no relationship to the campaign gets is unchanged
        // by #236: a 404 everywhere, so that no endpoint becomes an oracle for which
        // identifiers exist.
        assertThat(post("/v1/projects/" + project + "/updates", stranger.accessToken(), anUpdate())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getRaw("/v1/projects/" + project + "/referrers", stranger.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getRaw("/v1/projects/" + project + "/rewards", stranger.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
