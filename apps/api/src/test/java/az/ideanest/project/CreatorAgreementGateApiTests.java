package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.project.infrastructure.CategoryRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * The creator agreement gate on campaign submission — issue #426.
 *
 * <p><strong>Separate from {@code PublishingGateApiTests} for that suite's own reason.</strong>
 * It buys plans deliberately because the entitlement is its subject; this one publishes
 * agreements deliberately because the acceptance is. Both suites reach the same line in
 * {@code ProjectTransitionService.submit} and neither can be written as a variation of the
 * other without one of them becoming a fixture for the other's feature.
 *
 * <p><strong>The most important test here is {@link #aNewVersionDoesNotReachBackwards()}.</strong>
 * A rule that reached backwards would change what somebody agreed to after they agreed to
 * it, which is the one thing this epic exists to prevent — and it is the kind of behaviour
 * that arrives by accident, from a check written one line further out.
 */
class CreatorAgreementGateApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";
    private static final String ADMIN_EMAIL = "moderator@ideanest.test";

    private Account admin;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccessTokenIssuer tokens;

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM collaborator_capabilities");
        jdbc.update("DELETE FROM collaborators");
        jdbc.update("DELETE FROM project_state_transitions");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM subscriptions");
        jdbc.update("DELETE FROM document_acceptances");
        // See LegalDocumentApiTests on why the trigger is lifted for one statement: a
        // published version is immutable by design, and a suite that left rows behind would
        // gate every other suite's submissions.
        jdbc.execute("ALTER TABLE legal_documents DISABLE TRIGGER legal_documents_published_is_immutable");
        jdbc.update("DELETE FROM legal_documents");
        jdbc.execute("ALTER TABLE legal_documents ENABLE TRIGGER legal_documents_published_is_immutable");
    }

    @Test
    @DisplayName("with no creator agreement published, nothing is required and nothing is refused")
    void anUnpublishedAgreementIsNotARequirement() {
        Account creator = subscribedCreator();
        UUID project = completeCampaign(creator);

        // The fail-open case, and it is deliberate: a legal gate that failed closed would
        // refuse every campaign on the platform with a message telling creators to accept a
        // document nobody has written. See shared.legal.Agreements.
        assertThat(submit(project, creator).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a creator who has not accepted the agreement cannot submit, and is told which version")
    void anUnacceptedAgreementRefusesSubmission() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = subscribedCreator();
        UUID project = completeCampaign(creator);

        ResponseEntity<Map<String, Object>> refused = submit(project, creator);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("AGREEMENT_REQUIRED");
        // Not just the document. A creator who accepted version 3 and meets this because
        // version 4 landed this morning has to be sent to version 4.
        assertThat(metaOf(refused))
                .containsEntry("document", "CREATOR_AGREEMENT")
                .containsEntry("version", 1);
    }

    @Test
    @DisplayName("accepting it lets the campaign through")
    void anAcceptedAgreementAllowsSubmission() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = subscribedCreator();
        UUID project = completeCampaign(creator);

        assertThat(accept(creator, 1).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> submitted = submit(project, creator);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody().get("state")).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("a new version is required at the next submission and does not reach the last one")
    void aNewVersionDoesNotReachBackwards() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = subscribedCreator();
        accept(creator, 1);

        UUID underVersionOne = completeCampaign(creator);
        assertThat(submit(underVersionOne, creator).getStatusCode()).isEqualTo(HttpStatus.OK);

        publishCreatorAgreement("İkinci mətn.");

        // The campaign submitted under version 1 is left alone. Nothing re-reads the gate
        // for it, which is a property of where the check is rather than a rule written
        // anywhere -- and it is what stops a publication changing what somebody agreed to
        // after they agreed to it.
        assertThat(stateOf(underVersionOne)).isEqualTo("SUBMITTED");

        // The next one is measured against version 2.
        UUID next = completeCampaign(creator);
        ResponseEntity<Map<String, Object>> refused = submit(next, creator);
        assertThat(refused.getBody().get("code")).isEqualTo("AGREEMENT_REQUIRED");
        assertThat(metaOf(refused)).containsEntry("version", 2);

        accept(creator, 2);
        assertThat(submit(next, creator).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a version that does not govern yet is not required yet")
    void aFutureVersionIsNotRequiredYet() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = subscribedCreator();
        accept(creator, 1);

        publishCreatorAgreement("Gələcək mətn.", Instant.now().plusSeconds(86_400));

        // A change announced a fortnight before it bites must not bite today. That is the
        // whole reason effective_from is a column rather than the publication time.
        UUID project = completeCampaign(creator);
        assertThat(submit(project, creator).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a collaborator's acceptance does not satisfy the gate; the creator's does")
    void theCreatorAgrees() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = subscribedCreator();
        Account helper = subscribedCreator();
        accept(helper, 1);

        UUID project = completeCampaign(creator);
        grantSubmitCapability(project, creator, helper);

        // Otherwise a creator would take on no obligations at all by asking somebody else to
        // press the button -- and the campaign's §5.5 obligations would be owed by a person
        // with no control over it and no share of the money.
        ResponseEntity<Map<String, Object>> refused = submit(project, helper);
        assertThat(refused.getBody().get("code")).isEqualTo("AGREEMENT_REQUIRED");

        accept(creator, 1);
        assertThat(submit(project, helper).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the agreement is asked for before the plan, because the terms come before the price")
    void theAgreementIsAskedForFirst() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = account();
        UUID project = completeCampaign(creator);

        // This creator has neither. The refusal names the free, disclosing one -- a platform
        // that sent somebody to a price list before telling them the payout terms and the
        // fee would be selling before disclosing.
        assertThat(submit(project, creator).getBody().get("code")).isEqualTo("AGREEMENT_REQUIRED");

        accept(creator, 1);
        assertThat(submit(project, creator).getBody().get("code")).isEqualTo("SUBSCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("the account's own view says what is outstanding")
    void anAccountCanSeeWhatItOwes() {
        publishCreatorAgreement("Birinci mətn.");
        Account creator = subscribedCreator();

        Map<String, Object> before = agreementFor(creator, "CREATOR_AGREEMENT");
        assertThat(before.get("inForce")).isEqualTo(true);
        assertThat(before.get("version")).isEqualTo(1);
        assertThat(before.get("acceptedAt")).isNull();

        accept(creator, 1);
        assertThat(agreementFor(creator, "CREATOR_AGREEMENT").get("acceptedAt")).isNotNull();
    }

    /* ------------------------------------------------------------------
     * Fixtures
     * --------------------------------------------------------------- */

    private record Account(EmailAddress email, String accessToken, UUID id) {
    }

    private void publishCreatorAgreement(String body) {
        publishCreatorAgreement(body, null);
    }

    private void publishCreatorAgreement(String body, Instant effectiveFrom) {
        ResponseEntity<Map<String, Object>> drafted = rest.exchange(
                "/v1/admin/legal/documents/CREATOR_AGREEMENT/az/draft",
                HttpMethod.PUT,
                new HttpEntity<>(
                        Map.of("title", "Yaradıcı müqaviləsi", "body", body), authorised(admin().accessToken())),
                mapType());
        assertThat(drafted.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("effectiveFrom", effectiveFrom == null ? null : effectiveFrom.toString());

        ResponseEntity<Map<String, Object>> published = rest.exchange(
                "/v1/admin/legal/documents/CREATOR_AGREEMENT/publish",
                HttpMethod.POST,
                new HttpEntity<>(request, authorised(admin().accessToken())),
                mapType());
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map<String, Object>> accept(Account account, int version) {
        return rest.exchange(
                "/v1/me/agreements/CREATOR_AGREEMENT",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("version", version), authorised(account.accessToken())),
                mapType());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> agreementFor(Account account, String kind) {
        ResponseEntity<Map<String, Object>> mine = rest.exchange(
                "/v1/me/agreements", HttpMethod.GET, new HttpEntity<>(authorised(account.accessToken())), mapType());

        return ((java.util.List<Map<String, Object>>) mine.getBody().get("agreements"))
                .stream()
                        .filter(agreement -> kind.equals(agreement.get("document")))
                        .findFirst()
                        .orElseThrow();
    }

    private ResponseEntity<Map<String, Object>> submit(UUID projectId, Account actor) {
        return rest.exchange(
                "/v1/projects/" + projectId + "/submit",
                HttpMethod.POST,
                new HttpEntity<>(null, authorised(actor.accessToken())),
                mapType());
    }

    private String stateOf(UUID projectId) {
        return new JdbcTemplate(dataSource)
                .queryForObject("SELECT state FROM projects WHERE id = ?", String.class, projectId);
    }

    /** A creator whose only outstanding precondition is the agreement. */
    private Account subscribedCreator() {
        Account creator = account();
        Campaigns.subscribe(dataSource, creator.id());
        return creator;
    }

    private UUID completeCampaign(Account creator) {
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                "/v1/projects",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("title", "Agreement " + SEQUENCE.incrementAndGet()),
                        authorised(creator.accessToken())),
                mapType());
        UUID id = UUID.fromString((String) created.getBody().get("id"));

        rest.exchange(
                "/v1/projects/" + id,
                HttpMethod.PATCH,
                new HttpEntity<>(Campaigns.completeBasics(categories), authorised(creator.accessToken())),
                mapType());
        return id;
    }

    /** {@code PublishingGateApiTests}' fixture, and its argument for writing rather than inviting. */
    private void grantSubmitCapability(UUID projectId, Account creator, Account helper) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID collaboratorId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO collaborators
                    (id, project_id, account_id, invited_email, invitation_token_hash,
                     invited_by, expires_at, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, now() + interval '7 days', now())
                """,
                collaboratorId,
                projectId,
                helper.id(),
                helper.email().value(),
                new byte[32],
                creator.id());
        jdbc.update(
                "INSERT INTO collaborator_capabilities (collaborator_id, capability) VALUES (?, ?)",
                collaboratorId,
                "SUBMIT_FOR_REVIEW");
    }

    private Account admin() {
        if (admin != null) {
            return admin;
        }
        EmailAddress email = EmailAddress.of(ADMIN_EMAIL);
        if (users.findByEmailAndDeletedAtIsNull(email).isEmpty()) {
            rest.postForEntity(
                    "/v1/auth/register",
                    Map.of("email", email.value(), "password", PASSWORD, "name", "Test Administrator"),
                    String.class);
        }
        admin = tokenFor(email);
        return admin;
    }

    private Account account() {
        EmailAddress email = EmailAddress.of("agreement" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);
        return tokenFor(email);
    }

    /**
     * A token issued rather than signed in for.
     *
     * <p>{@code sign-ins-per-email} is realistically five and a dozen suites share the
     * administrator's address; a suite that took a sign-in would exhaust the limiter for
     * whichever ran after it, and the symptom is a 401 three assertions away.
     */
    private Account tokenFor(EmailAddress email) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("meta");
    }

    private static ParameterizedTypeReference<Map<String, Object>> mapType() {
        return new ParameterizedTypeReference<>() {};
    }

    private static HttpHeaders authorised(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
