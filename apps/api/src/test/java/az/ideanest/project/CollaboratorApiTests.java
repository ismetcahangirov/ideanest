package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.domain.ProjectStateTransition;
import az.ideanest.project.infrastructure.CategoryRepository;
import az.ideanest.project.infrastructure.ProjectStateTransitionRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.SecureTokens;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.CampaignFixtures;
import az.ideanest.support.RecordingCollaboratorInvitationNotifier;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Collaborators, over HTTP.
 *
 * <p>The tests that carry the design are
 * {@link #theInvitationTokenIsNeverStoredInTheClear()} — the property the whole
 * invitation flow rests on — {@link #anActionOutsideTheGrantIsForbidden()}, which is
 * the difference between granular capabilities and a checkbox that means "trusted",
 * and {@link #aCollaboratorsSubmissionIsAuditedAsACollaborator()}, which is why
 * {@code roleOf} exists at all.
 *
 * <p>{@link CollaboratorGrantTests} proves the rules; this proves they are wired up.
 */
class CollaboratorApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ProjectStateTransitionRepository transitions;

    @Autowired
    private RecordingCollaboratorInvitationNotifier invitations;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearProjectsAndInvitations() {
        // Campaigns reference users and deliberately do not cascade from them, so a
        // suite that left rows here would break the identity tests' own cleanup.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM collaborators");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
        invitations.clear();
        // The clock is a shared bean. A test that froze it and did not put it back
        // would break every later test that reasons about expiry.
        clock.reset();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A registered, signed-in account: its address, its access token, its identifier. */
    private record Account(EmailAddress email, String accessToken, UUID id) {
    }

    private Account account(String prefix) {
        EmailAddress email = EmailAddress.of(prefix + SEQUENCE.incrementAndGet() + "@example.com");
        register(email);
        return signIn(email);
    }

    private void register(EmailAddress email) {
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);
    }

    private Account signIn(EmailAddress email) {
        ResponseEntity<Map<String, Object>> signedIn = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("email", email.value(), "password", PASSWORD, "tokenDelivery", "body"), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account(email, (String) signedIn.getBody().get("accessToken"), id);
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
        return rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, bearer(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> patch(String path, String accessToken, Map<String, Object> body) {
        return rest.exchange(
                path,
                HttpMethod.PATCH,
                new HttpEntity<>(body, bearer(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> get(String path, String accessToken) {
        return rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, String accessToken) {
        return rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    /**
     * A GET whose body is not read.
     *
     * <p>For asserting a refusal. {@link #getList} asks Jackson for a list, and a
     * refusal answers a problem detail — an object — so the conversion fails before
     * the status can be looked at, and the test reports a parse error instead of the
     * 404 it was checking for. Reading the body as a string sidesteps that: this
     * helper is about the status line and nothing else.
     */
    private ResponseEntity<String> getRaw(String path, String accessToken) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), String.class);
    }

    private ResponseEntity<Map<String, Object>> delete(String path, String accessToken) {
        return rest.exchange(
                path,
                HttpMethod.DELETE,
                new HttpEntity<>(bearer(accessToken)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * A draft §5.3 is satisfied with, in the state a submission starts from.
     *
     * <p>Complete rather than merely fundable, because the submission endpoint now
     * re-checks the completeness checklist: an incomplete fixture would make every
     * test in this file that submits fail with {@code PROJECT_NOT_SUBMITTABLE},
     * which says nothing about the capability each of them is really about.
     */
    private UUID submittableDraft(Account creator) {
        Map<String, Object> project = post("/v1/projects", creator.accessToken(), Map.of("title", "A campaign"))
                .getBody();
        UUID id = UUID.fromString((String) project.get("id"));

        patch("/v1/projects/" + id, creator.accessToken(), CampaignFixtures.completeBasics(categories));
        return id;
    }

    private ResponseEntity<Map<String, Object>> invite(
            UUID projectId, Account inviter, EmailAddress email, String... capabilities) {

        return post(
                "/v1/projects/" + projectId + "/collaborators",
                inviter.accessToken(),
                Map.of("email", email.value(), "capabilities", List.of(capabilities)));
    }

    /** An accepted grant: invited, then claimed by an account of that address. */
    private Account collaborator(UUID projectId, Account inviter, String... capabilities) {
        Account invitee = account("collaborator");
        ResponseEntity<Map<String, Object>> invited = invite(projectId, inviter, invitee.email(), capabilities);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String token = invitations.tokenSentTo(invitee.email()).orElseThrow();
        assertThat(accept(token, invitee).getStatusCode()).isEqualTo(HttpStatus.OK);
        return invitee;
    }

    private ResponseEntity<Map<String, Object>> accept(String token, Account account) {
        return post("/v1/collaborators/invitations/" + token + "/accept", account.accessToken(), null);
    }

    private List<ProjectStateTransition> historyOf(UUID projectId) {
        return transitions.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    // ------------------------------------------------------------------
    // Inviting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inviting an address creates a pending grant and sends the link to that address")
    void invitingAnAddress() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        EmailAddress invitee = EmailAddress.of("designer" + SEQUENCE.incrementAndGet() + "@example.com");

        ResponseEntity<Map<String, Object>> invited = invite(projectId, creator, invitee, "EDIT_STORY");

        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = invited.getBody();
        assertThat(body).containsEntry("email", invitee.value());
        assertThat(body).containsEntry("status", "PENDING");
        assertThat(body).containsEntry("capabilities", List.of("EDIT_STORY"));
        assertThat(body).containsEntry("invitedById", creator.id().toString());
        // No account yet, and that is the ordinary case rather than an edge one: the
        // creator invited an address, not a row identifier.
        assertThat(body).containsEntry("accountId", null);
        assertThat(body).containsEntry("acceptedAt", null);
        assertThat(body.get("expiresAt")).isNotNull();
        // The invitation link is not in the response. A creator who could read the
        // token out of their own response could accept on the invitee's behalf, which
        // is the one thing sending it to the address is for.
        assertThat(body).doesNotContainKey("token");

        assertThat(invited.getHeaders().getLocation()).hasToString("/v1/collaborators/" + body.get("id"));
        assertThat(invitations.invitationsSentTo(invitee)).isEqualTo(1);
    }

    @Test
    @DisplayName("the invitation token is never stored in the clear")
    void theInvitationTokenIsNeverStoredInTheClear() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        EmailAddress invitee = EmailAddress.of("designer" + SEQUENCE.incrementAndGet() + "@example.com");

        invite(projectId, creator, invitee, "EDIT_STORY");
        String token = invitations.tokenSentTo(invitee).orElseThrow();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // What is stored is the SHA-256 of what was sent. Whoever can read this table
        // therefore cannot use what they find, which is the entire reason the column
        // is a hash — the same decision as verification_tokens and refresh_tokens.
        byte[] stored = jdbc.queryForObject(
                "SELECT invitation_token_hash FROM collaborators WHERE invited_email = ?::citext",
                byte[].class,
                invitee.value());
        assertThat(stored).isEqualTo(SecureTokens.hash(token));
        assertThat(stored).hasSize(32);

        // And the token itself appears nowhere in the row. Cast to text and searched
        // rather than checked column by column, so that a column added later is
        // covered by this test without anybody remembering to extend it.
        Integer leaked = jdbc.queryForObject(
                "SELECT count(*) FROM collaborators WHERE strpos(collaborators::text, ?) > 0",
                Integer.class,
                token);
        assertThat(leaked).isZero();
    }

    @Test
    @DisplayName("the creator cannot be invited onto their own campaign")
    void theCreatorIsNotACollaborator() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);

        ResponseEntity<Map<String, Object>> refused = invite(projectId, creator, creator.email(), "EDIT_BASICS");

        // Their authority is implicit and is not a row. A row could be revoked or
        // narrowed, which would be a way to lock somebody out of their own campaign.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "PROJECT_FIELD_INVALID");
        assertThat(refused.getBody().get("meta")).isEqualTo(Map.of("field", "email"));
    }

    @Test
    @DisplayName("inviting the same address twice is a conflict, and revoking clears the way")
    void oneLiveInvitationPerAddress() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        EmailAddress invitee = EmailAddress.of("designer" + SEQUENCE.incrementAndGet() + "@example.com");

        Map<String, Object> first = invite(projectId, creator, invitee, "EDIT_STORY").getBody();

        // A second invitation would put two links in circulation for one person while
        // the creator believed they had replaced the first.
        ResponseEntity<Map<String, Object>> second = invite(projectId, creator, invitee, "EDIT_BASICS");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "COLLABORATOR_ALREADY_INVITED");

        assertThat(delete("/v1/collaborators/" + first.get("id"), creator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(invite(projectId, creator, invitee, "EDIT_BASICS").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a capability list has to name at least one capability")
    void aGrantConfersSomething() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);

        ResponseEntity<Map<String, Object>> refused = post(
                "/v1/projects/" + projectId + "/collaborators",
                creator.accessToken(),
                Map.of("email", "nobody@example.com", "capabilities", List.of()));

        // A collaborator with nothing granted is a person told they are on a campaign
        // they cannot touch.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an editing grant reaches only the fields it names")
    void theThreeEditingGrantsAreNotOneGrant() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account writer = collaborator(projectId, creator, "EDIT_STORY");

        // What they were invited for.
        assertThat(patch("/v1/projects/" + projectId, writer.accessToken(), Map.of("risks", "Tooling."))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        /*
         * And nothing else. One endpoint carries the basics, the story and the risks
         * section, so if the write path accepted any editing capability the three
         * grants would be one grant with three names -- somebody invited to write the
         * story could move the funding goal, and the boxes the creator ticked would
         * not mean what they say.
         */
        ResponseEntity<Map<String, Object>> refused = patch(
                "/v1/projects/" + projectId,
                writer.accessToken(),
                Map.of("goal", Map.of("amount", "9000.00", "currency", "AZN")));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "CAPABILITY_NOT_GRANTED");

        // A body that mixes the two is refused whole, so half of it cannot land.
        assertThat(patch(
                                "/v1/projects/" + projectId,
                                writer.accessToken(),
                                Map.of("risks", "Shipping.", "title", "A renamed campaign"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/projects/" + projectId + "/edit", creator.accessToken()).getBody())
                .containsEntry("title", "A campaign")
                .containsEntry("risks", "Tooling.");
    }

    @Test
    @DisplayName("a stranger cannot see or manage a campaign's team")
    void theTeamIsPrivateToTheCampaign() {
        Account creator = account("creator");
        Account stranger = account("stranger");
        UUID projectId = submittableDraft(creator);

        // 404 rather than 403: a draft is confidential, and answering "forbidden"
        // would confirm that the campaign exists.
        assertThat(getRaw("/v1/projects/" + projectId + "/collaborators", stranger.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invite(projectId, stranger, stranger.email(), "EDIT_BASICS")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the list shows pending, active, and revoked alike")
    void theListShowsEveryone() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account active = collaborator(projectId, creator, "EDIT_BASICS");
        EmailAddress pending = EmailAddress.of("pending" + SEQUENCE.incrementAndGet() + "@example.com");
        invite(projectId, creator, pending, "EDIT_STORY");
        Account leaving = collaborator(projectId, creator, "VIEW_FINANCES");
        String leavingId = (String) findByEmail(projectId, creator, leaving.email()).get("id");
        delete("/v1/collaborators/" + leavingId, creator.accessToken());

        List<Map<String, Object>> team =
                getList("/v1/projects/" + projectId + "/collaborators", creator.accessToken()).getBody();

        // Revoked rows are shown on purpose: a list that hid them would make the
        // revocation look as though it had never happened, and "who used to have
        // access" is what this list is asked after a leak.
        assertThat(team).hasSize(3);
        assertThat(statusOf(team, active.email())).isEqualTo("ACTIVE");
        assertThat(statusOf(team, pending)).isEqualTo("PENDING");
        assertThat(statusOf(team, leaving.email())).isEqualTo("REVOKED");
    }

    // ------------------------------------------------------------------
    // Accepting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("accepting an invitation attaches the account and activates the grant")
    void acceptingAnInvitation() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account invitee = account("collaborator");
        invite(projectId, creator, invitee.email(), "EDIT_BASICS");

        Map<String, Object> accepted = accept(
                        invitations.tokenSentTo(invitee.email()).orElseThrow(), invitee)
                .getBody();

        assertThat(accepted).containsEntry("status", "ACTIVE");
        assertThat(accepted).containsEntry("accountId", invitee.id().toString());
        assertThat(accepted.get("acceptedAt")).isNotNull();
    }

    @Test
    @DisplayName("an invitation to an address with no account waits for it to register")
    void anInvitationToAnUnregisteredAddress() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        // Nobody has this address. Which is the normal case: a creator invites the
        // colleague whose address they know, not one who has already registered.
        EmailAddress invitee = EmailAddress.of("newcomer" + SEQUENCE.incrementAndGet() + "@example.com");

        assertThat(invite(projectId, creator, invitee, "EDIT_STORY").getBody())
                .containsEntry("status", "PENDING")
                .containsEntry("accountId", null);

        String token = invitations.tokenSentTo(invitee).orElseThrow();

        // The invitation is claimed once that address exists and follows the link. The
        // account is attached at acceptance rather than at registration, so the
        // capability is granted by somebody who has proved they hold the mailbox.
        register(invitee);
        Account newcomer = signIn(invitee);

        Map<String, Object> accepted = accept(token, newcomer).getBody();
        assertThat(accepted).containsEntry("status", "ACTIVE");
        assertThat(accepted).containsEntry("accountId", newcomer.id().toString());

        assertThat(patch("/v1/projects/" + projectId, newcomer.accessToken(), Map.of("risks", "A paragraph."))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("acceptance is single use")
    void acceptanceIsSingleUse() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account invitee = account("collaborator");
        invite(projectId, creator, invitee.email(), "EDIT_BASICS");
        String token = invitations.tokenSentTo(invitee.email()).orElseThrow();

        assertThat(accept(token, invitee).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Spent by a timestamp rather than by deleting the row, so a replay is
        // distinguishable from a token that never existed — one is somebody
        // double-clicking, the other is somebody guessing.
        ResponseEntity<Map<String, Object>> again = accept(token, invitee);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "INVITATION_REJECTED");
        assertThat(again.getBody().get("detail")).asString().contains("already been used");
    }

    @Test
    @DisplayName("an expired invitation cannot be accepted")
    void anExpiredInvitationIsRefused() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account invitee = account("collaborator");
        invite(projectId, creator, invitee.email(), "EDIT_BASICS");
        String token = invitations.tokenSentTo(invitee.email()).orElseThrow();

        // Past the seven days of ideanest.project.collaborators.invitation-ttl. An
        // invitation is not a standing offer: a link that worked forever would be a
        // key to an unlaunched campaign sitting in an old mailbox.
        clock.advance(Duration.ofDays(8));

        ResponseEntity<Map<String, Object>> refused = accept(token, invitee);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("detail")).asString().contains("expired");
    }

    @Test
    @DisplayName("a revoked invitation cannot be accepted afterwards")
    void aRevokedInvitationIsRefused() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account invitee = account("collaborator");
        String collaboratorId = (String)
                invite(projectId, creator, invitee.email(), "EDIT_BASICS").getBody().get("id");
        String token = invitations.tokenSentTo(invitee.email()).orElseThrow();

        delete("/v1/collaborators/" + collaboratorId, creator.accessToken());

        // The link is in a mailbox, and withdrawing the invitation has to reach it.
        // Deleting the row would have made this indistinguishable from a bad token.
        ResponseEntity<Map<String, Object>> refused = accept(token, invitee);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("detail")).asString().contains("withdrawn");
    }

    @Test
    @DisplayName("an invitation belongs to the address it was sent to, not to whoever holds the link")
    void aForwardedInvitationGrantsNothing() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account invitee = account("collaborator");
        Account somebodyElse = account("stranger");
        invite(projectId, creator, invitee.email(), "EDIT_BASICS");
        String token = invitations.tokenSentTo(invitee.email()).orElseThrow();

        // Without this the invitee could forward the message and put anybody on the
        // campaign, and the creator would have no way to tell.
        ResponseEntity<Map<String, Object>> refused = accept(token, somebodyElse);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("detail")).asString().contains("different address");

        // And the invitation is still there for the person it was meant for.
        assertThat(accept(token, invitee).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an invented token is refused without saying anything about it")
    void anUnknownTokenIsRefused() {
        Account account = account("stranger");

        ResponseEntity<Map<String, Object>> refused = accept("not-a-real-token", account);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("detail")).asString().isEqualTo("This invitation is not valid.");
    }

    // ------------------------------------------------------------------
    // Acting under a grant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collaborator can read and edit the campaign they were granted")
    void aCollaboratorActsUnderTheirGrant() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account editor = collaborator(projectId, creator, "EDIT_BASICS");

        assertThat(get("/v1/projects/" + projectId + "/edit", editor.accessToken())
                        .getBody())
                .containsEntry("title", "A campaign");

        Map<String, Object> saved = patch(
                        "/v1/projects/" + projectId, editor.accessToken(), Map.of("title", "A better title"))
                .getBody();
        assertThat(saved).containsEntry("title", "A better title");

        // And the creator sees the collaborator's work, which is the point of the
        // feature rather than an incidental detail.
        assertThat(get("/v1/projects/" + projectId + "/edit", creator.accessToken())
                        .getBody())
                .containsEntry("title", "A better title");
    }

    @Test
    @DisplayName("an action outside the grant is a 403 naming what would have authorised it")
    void anActionOutsideTheGrantIsForbidden() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account storyEditor = collaborator(projectId, creator, "EDIT_STORY");

        // 403 rather than 404. The reasoning that makes a draft answer 404 to a
        // stranger — a draft is confidential — has nothing left to protect from
        // somebody who was invited and can already read the campaign.
        ResponseEntity<Map<String, Object>> refused =
                post("/v1/projects/" + projectId + "/submit", storyEditor.accessToken(), null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).containsEntry("code", "CAPABILITY_NOT_GRANTED");
        assertThat(refused.getBody().get("meta"))
                .isEqualTo(Map.of("requiredAnyOf", List.of("SUBMIT_FOR_REVIEW"), "held", List.of("EDIT_STORY")));

        // Nothing moved, and no audit row claims otherwise.
        assertThat(historyOf(projectId)).hasSize(1);
    }

    @Test
    @DisplayName("a collaborator who is not an editor cannot open the editor")
    void aFinanceViewerIsNotAnEditor() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account accountant = collaborator(projectId, creator, "VIEW_FINANCES");

        ResponseEntity<Map<String, Object>> refused = get("/v1/projects/" + projectId + "/edit", accountant.accessToken());

        // The coarse check admits anybody granted an editing capability, and this
        // grant is not one of them. The refusal says which three would have done.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("meta"))
                .isEqualTo(Map.of(
                        "requiredAnyOf",
                        List.of("EDIT_BASICS", "EDIT_REWARDS", "EDIT_STORY"),
                        "held",
                        List.of("VIEW_FINANCES")));
    }

    @Test
    @DisplayName("launching and cancelling belong to the creator, whatever a collaborator holds")
    void theMoneyDecisionsStayWithTheCreator() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account trusted = collaborator(
                projectId, creator, "EDIT_BASICS", "SUBMIT_FOR_REVIEW", "VIEW_FINANCES", "PUBLISH_UPDATES");

        // Going live starts taking pledges and cancelling abandons commitments people
        // have already made with their card details on file. No capability confers
        // either, which is a stronger statement than "all of them" — so the response
        // names no capability at all rather than sending the collaborator to ask for
        // one that does not exist.
        for (String action : List.of("launch", "cancel")) {
            ResponseEntity<Map<String, Object>> refused = post(
                    "/v1/projects/" + projectId + "/" + action,
                    trusted.accessToken(),
                    Map.of("reason", "Changed my mind"));
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody()).containsEntry("code", "CAPABILITY_NOT_GRANTED");
            assertThat(refused.getBody().get("detail")).asString().contains("creator");
            assertThat(((Map<?, ?>) refused.getBody().get("meta")).get("requiredAnyOf"))
                    .isEqualTo(List.of());
        }
    }

    @Test
    @DisplayName("a collaborator's submission is audited as a collaborator")
    void aCollaboratorsSubmissionIsAuditedAsACollaborator() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account submitter = collaborator(projectId, creator, "SUBMIT_FOR_REVIEW");

        assertThat(post("/v1/projects/" + projectId + "/submit", submitter.accessToken(), null)
                        .getBody())
                .containsEntry("state", "SUBMITTED");

        List<ProjectStateTransition> history = historyOf(projectId);

        // The row has to say which of the two a person was: "the creator submitted
        // this" and "somebody the creator trusted with one capability submitted it"
        // are different facts, and a year later this row is the only place either of
        // them still exists.
        assertThat(history).hasSize(2);
        assertThat(history.getLast().getToState()).isEqualTo(ProjectState.SUBMITTED);
        assertThat(history.getLast().getActorRole()).isEqualTo(ActorRole.COLLABORATOR);
        assertThat(history.getLast().getActorId()).isEqualTo(submitter.id());
    }

    // ------------------------------------------------------------------
    // Changing and revoking
    // ------------------------------------------------------------------

    @Test
    @DisplayName("narrowing a grant takes effect immediately")
    void narrowingAGrant() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account editor = collaborator(projectId, creator, "EDIT_BASICS", "SUBMIT_FOR_REVIEW");
        String collaboratorId = (String) findByEmail(projectId, creator, editor.email()).get("id");

        Map<String, Object> narrowed = patch(
                        "/v1/collaborators/" + collaboratorId,
                        creator.accessToken(),
                        Map.of("capabilities", List.of("EDIT_BASICS")))
                .getBody();
        assertThat(narrowed).containsEntry("capabilities", List.of("EDIT_BASICS"));

        // The capability that was taken away stops working on the next request, not
        // when a cache expires: the check reads the row.
        assertThat(post("/v1/projects/" + projectId + "/submit", editor.accessToken(), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch("/v1/projects/" + projectId, editor.accessToken(), Map.of("title", "Still allowed"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("revoking a grant ends access, and the revoked collaborator is a stranger again")
    void revokingEndsAccess() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account editor = collaborator(projectId, creator, "EDIT_BASICS");
        String collaboratorId = (String) findByEmail(projectId, creator, editor.email()).get("id");

        assertThat(delete("/v1/collaborators/" + collaboratorId, creator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // 404, not 403: the confidentiality that a 404 protects is exactly what the
        // revocation withdrew, so telling them the campaign is still there would leak
        // what they were removed from.
        assertThat(get("/v1/projects/" + projectId + "/edit", editor.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/v1/projects/" + projectId, editor.accessToken(), Map.of("title", "Mine now"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Revoking twice is not an error: the client asked for a state the row is
        // already in, and a retried DELETE should not look like a failure.
        assertThat(delete("/v1/collaborators/" + collaboratorId, creator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The row remains, as the record that they had access between two dates.
        assertThat(statusOf(
                        getList("/v1/projects/" + projectId + "/collaborators", creator.accessToken())
                                .getBody(),
                        editor.email()))
                .isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("a withdrawn grant cannot be edited back into life")
    void aRevokedGrantCannotBeChanged() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account editor = collaborator(projectId, creator, "EDIT_BASICS");
        String collaboratorId = (String) findByEmail(projectId, creator, editor.email()).get("id");
        delete("/v1/collaborators/" + collaboratorId, creator.accessToken());

        ResponseEntity<Map<String, Object>> refused = patch(
                "/v1/collaborators/" + collaboratorId,
                creator.accessToken(),
                Map.of("capabilities", List.of("EDIT_BASICS")));

        // Restoring access is an invitation, which the person has to accept again. A
        // grant edited back into force would authorise somebody who has not agreed to
        // anything since it was withdrawn.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code", "INVITATION_REJECTED");
    }

    // ------------------------------------------------------------------
    // Delegation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a manager cannot grant more than they hold, and cannot pass on MANAGE_COLLABORATORS")
    void noEscalationOverHttp() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account manager = collaborator(projectId, creator, "MANAGE_COLLABORATORS", "EDIT_STORY");
        EmailAddress candidate = EmailAddress.of("candidate" + SEQUENCE.incrementAndGet() + "@example.com");

        // Within their own grant.
        assertThat(invite(projectId, manager, candidate, "EDIT_STORY").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Beyond it. Otherwise the creator's decision to grant one capability is
        // advisory: the manager invites an accomplice with all eight.
        EmailAddress accomplice = EmailAddress.of("accomplice" + SEQUENCE.incrementAndGet() + "@example.com");
        ResponseEntity<Map<String, Object>> escalated = invite(projectId, manager, accomplice, "VIEW_FINANCES");
        assertThat(escalated.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(escalated.getBody()).containsEntry("code", "CAPABILITY_NOT_GRANTED");
        assertThat(((Map<?, ?>) escalated.getBody().get("meta")).get("requiredAnyOf"))
                .isEqualTo(List.of("VIEW_FINANCES"));

        // And the capability they do hold is still not one they may confer: a manager
        // who could pass it on could grow the team indefinitely, and so could everyone
        // they added.
        assertThat(invite(projectId, manager, accomplice, "MANAGE_COLLABORATORS")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invite(projectId, creator, accomplice, "MANAGE_COLLABORATORS")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a collaborator without MANAGE_COLLABORATORS cannot invite anybody")
    void managingIsItsOwnCapability() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account editor = collaborator(projectId, creator, "EDIT_BASICS");

        ResponseEntity<Map<String, Object>> refused =
                invite(projectId, editor, EmailAddress.of("nobody@example.com"), "EDIT_BASICS");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(((Map<?, ?>) refused.getBody().get("meta")).get("requiredAnyOf"))
                .isEqualTo(List.of("MANAGE_COLLABORATORS"));
    }

    @Test
    @DisplayName("a manager narrowing their own grant cannot widen it")
    void aManagerCannotWidenAGrantBeyondTheirOwn() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);
        Account manager = collaborator(projectId, creator, "MANAGE_COLLABORATORS", "EDIT_STORY");
        Account editor = collaborator(projectId, creator, "EDIT_STORY");
        String editorId = (String) findByEmail(projectId, creator, editor.email()).get("id");

        // Otherwise "grant no more than you hold" would be a rule about invitations
        // rather than about grants, and the way around it would be to invite somebody
        // narrowly and widen them a second later.
        assertThat(patch(
                                "/v1/collaborators/" + editorId,
                                manager.accessToken(),
                                Map.of("capabilities", List.of("VIEW_FINANCES")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch(
                                "/v1/collaborators/" + editorId,
                                manager.accessToken(),
                                Map.of("capabilities", List.of("EDIT_STORY")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("collaborator endpoints are behind authentication")
    void everythingIsBehindAuthentication() {
        Account creator = account("creator");
        UUID projectId = submittableDraft(creator);

        assertThat(rest.exchange(
                                "/v1/projects/" + projectId + "/collaborators",
                                HttpMethod.GET,
                                new HttpEntity<>(jsonHeaders()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(
                                "/v1/collaborators/invitations/some-token/accept",
                                HttpMethod.POST,
                                new HttpEntity<>(jsonHeaders()),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Helpers over the list projection
    // ------------------------------------------------------------------

    private Map<String, Object> findByEmail(UUID projectId, Account manager, EmailAddress email) {
        return getList("/v1/projects/" + projectId + "/collaborators", manager.accessToken()).getBody().stream()
                .filter(row -> email.value().equals(row.get("email")))
                .findFirst()
                .orElseThrow();
    }

    private static String statusOf(List<Map<String, Object>> team, EmailAddress email) {
        return (String) team.stream()
                .filter(row -> email.value().equals(row.get("email")))
                .findFirst()
                .orElseThrow()
                .get("status");
    }
}
