package az.ideanest.legal;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.application.AccessTokenIssuer;
import az.ideanest.legal.domain.DocumentKind;
import az.ideanest.legal.domain.LegalDocument;
import az.ideanest.legal.infrastructure.DocumentAcceptanceRepository;
import az.ideanest.legal.infrastructure.LegalDocumentRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.ReaderLocale;
import az.ideanest.shared.legal.AgreementInForce;
import az.ideanest.shared.legal.AgreementKind;
import az.ideanest.shared.legal.Agreements;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.SimaImzaStub;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Signing the creator agreement, and binding the signature to the acceptance — issue #429.
 *
 * <p>The real SİMA adapter runs against {@code SimaImzaStub}, so everything between the
 * platform and the wire is the code a deployment runs. What is scripted is only what the
 * citizen did, which is the part no sandbox could be made to do on demand.
 *
 * <p>The three cases #429 names are here one test each: the hash binding (a signature over a
 * different version does not satisfy the current one), the name match against #430
 * ({@code MISMATCHED_NAME}), and a cancelled signature leaving nothing behind.
 */
@DisplayName("Signing the creator agreement")
class AgreementSigningApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";
    private static final String CREATOR_NAME = "Aygün Məmmədova";
    private static final String FIN = "1ABC234";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private Agreements agreements;

    @Autowired
    private LegalDocumentRepository documents;

    @Autowired
    private DocumentAcceptanceRepository acceptances;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void publishTheAgreement() {
        SimaImzaStub.reset();
        clearLegalRows();
        publish(AgreementKind.CREATOR_AGREEMENT, 1, "Yaradıcı müqaviləsi", "The obligations, version one.");
    }

    @AfterEach
    void clear() {
        clearLegalRows();
    }

    // ------------------------------------------------------------------
    // The signature, and what it is over
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a signature over the version in force is filed against the acceptance")
    void aSignatureIsFiled() {
        Account creator = creatorWithLegalSubject();
        AgreementInForce agreement = agreements.inForce(AgreementKind.CREATOR_AGREEMENT).orElseThrow();

        String sessionId = SimaImzaStub.willBeginSession();
        Map<String, Object> opened = begin(creator);
        assertThat(opened.get("sessionId")).isEqualTo(sessionId);
        assertThat(opened.get("verificationCode")).isEqualTo("7391");

        SimaImzaStub.willResolveSigned(sessionId, governingHash(), CREATOR_NAME, FIN);
        Map<String, Object> resolved = resolve(creator, sessionId);

        assertThat(resolved.get("state")).isEqualTo("SIGNED");
        assertThat(resolved.get("signed")).isEqualTo(true);
        assertThat(resolved.get("signerName")).isEqualTo(CREATOR_NAME);

        // The binding, which is the point: the acceptance now names a signature.
        assertThat(agreements.hasAccepted(creator.id(), agreement)).isTrue();
        assertThat(agreements.hasSigned(creator.id(), agreement)).isTrue();
        assertThat(acceptances
                        .find(creator.id(), agreement.documentId())
                        .orElseThrow()
                        .getSignatureId())
                .isNotNull();
    }

    @Test
    @DisplayName("a signature over a different version does not satisfy the one in force")
    void aSignatureOverAnotherVersion() {
        Account creator = creatorWithLegalSubject();
        AgreementInForce agreement = agreements.inForce(AgreementKind.CREATOR_AGREEMENT).orElseThrow();

        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);

        // The citizen's phone was in their pocket while a new version was published, so what
        // comes back is a sound signature over the text they were shown.
        SimaImzaStub.willResolveSigned(sessionId, "b".repeat(64), CREATOR_NAME, FIN);
        ResponseEntity<Map<String, Object>> refused = resolveRaw(creator, sessionId);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("SIGNATURE_VERSION_STALE");

        // Nothing is written. A signature over a superseded text binding somebody to the
        // current one is the substitution a content hash exists to make impossible.
        assertThat(agreements.hasAccepted(creator.id(), agreement)).isFalse();
    }

    @Test
    @DisplayName("a certificate naming somebody else is refused with MISMATCHED_NAME")
    void aCertificateNamingSomebodyElse() {
        Account creator = creatorWithLegalSubject();
        AgreementInForce agreement = agreements.inForce(AgreementKind.CREATOR_AGREEMENT).orElseThrow();

        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolveSigned(sessionId, governingHash(), "Elvin Məmmədov", "9XYZ876");

        ResponseEntity<Map<String, Object>> refused = resolveRaw(creator, sessionId);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The closed set V58 already uses, rather than a second vocabulary for one idea.
        assertThat(refused.getBody().get("code")).isEqualTo("MISMATCHED_NAME");
        assertThat(((Map<?, ?>) refused.getBody().get("meta")).get("reason")).isEqualTo("MISMATCHED_NAME");
        assertThat(agreements.hasAccepted(creator.id(), agreement)).isFalse();
    }

    @Test
    @DisplayName("case and spacing are not a mismatch, because that refusal would mean 'you pressed shift'")
    void presentationIsNotIdentity() {
        Account creator = creatorWithLegalSubject();
        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolveSigned(sessionId, governingHash(), "AYGÜN  MƏMMƏDOVA", FIN);

        assertThat(resolve(creator, sessionId).get("state")).isEqualTo("SIGNED");
    }

    // ------------------------------------------------------------------
    // What a refusal leaves behind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cancelled signature leaves the draft untouched")
    void aCancellationLeavesNothing() {
        Account creator = creatorWithLegalSubject();
        AgreementInForce agreement = agreements.inForce(AgreementKind.CREATOR_AGREEMENT).orElseThrow();

        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolveCancelled(sessionId);

        Map<String, Object> resolved = resolve(creator, sessionId);

        // A value and not an error. §9.4's distinction, which #428 keeps and this depends on:
        // a citizen who declines is an ordinary outcome shown as "not signed yet".
        assertThat(resolved.get("state")).isEqualTo("CANCELLED");
        assertThat(resolved.get("signed")).isEqualTo(false);
        assertThat(agreements.hasAccepted(creator.id(), agreement)).isFalse();
    }

    @Test
    @DisplayName("a session nobody has answered polls as pending, and the second poll asks again")
    void pendingPolls() {
        Account creator = creatorWithLegalSubject();
        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolvePending(sessionId);

        assertThat(resolve(creator, sessionId).get("state")).isEqualTo("PENDING");
        assertThat(resolve(creator, sessionId).get("state")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a resolved session answers from the row, so one act of signing is one signature")
    void aResolvedSessionIsNotResolvedTwice() {
        Account creator = creatorWithLegalSubject();
        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolveSigned(sessionId, governingHash(), CREATOR_NAME, FIN);

        Object first = resolve(creator, sessionId).get("signatureId");

        // SİMA stops answering. The platform must still know what happened, because the row is
        // its own record and a second `signatures` row for one act of signing would be worse
        // than no answer.
        SimaImzaStub.reset();
        Map<String, Object> again = resolve(creator, sessionId);

        assertThat(again.get("state")).isEqualTo("SIGNED");
        assertThat(again.get("signatureId")).isEqualTo(first);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM signatures", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("somebody else's session is not resolvable, and the refusal says nothing about whose")
    void aSessionIsNotABearerToken() {
        Account creator = creatorWithLegalSubject();
        Account stranger = creatorWithLegalSubject();
        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolveSigned(sessionId, governingHash(), CREATOR_NAME, FIN);

        ResponseEntity<Map<String, Object>> refused = resolveRaw(stranger, sessionId);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody().get("code")).isEqualTo("UNKNOWN_SIGNING_SESSION");
    }

    // ------------------------------------------------------------------
    // Preconditions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a creator with no legal subject is sent to record one before signing")
    void aLegalSubjectIsRequiredFirst() {
        Account creator = account();

        ResponseEntity<Map<String, Object>> refused = beginRaw(creator);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("LEGAL_SUBJECT_REQUIRED");
        assertThat(((Map<?, ?>) refused.getBody().get("meta")).get("next")).isEqualTo("/v1/me/legal-subject");
    }

    @Test
    @DisplayName("signing a stale version is refused before SİMA is asked anything")
    void aStaleVersionIsRefusedAtTheStart() {
        Account creator = creatorWithLegalSubject();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 7);
        body.put("fin", FIN);
        body.put("mobile", "+994501112233");

        ResponseEntity<Map<String, Object>> refused = rest.exchange(
                "/v1/me/agreements/CREATOR_AGREEMENT/signature",
                HttpMethod.POST,
                new HttpEntity<>(body, authorised(creator.accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("AGREEMENT_VERSION_STALE");
    }

    // ------------------------------------------------------------------
    // Reading it back
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the creator can see what they signed, when, and which version")
    void theCreatorCanRetrieveIt() {
        Account creator = creatorWithLegalSubject();
        String sessionId = SimaImzaStub.willBeginSession();
        begin(creator);
        SimaImzaStub.willResolveSigned(sessionId, governingHash(), CREATOR_NAME, FIN);
        resolve(creator, sessionId);

        ResponseEntity<Map<String, Object>> mine = rest.exchange(
                "/v1/me/agreements/CREATOR_AGREEMENT/signature",
                HttpMethod.GET,
                new HttpEntity<>(authorised(creator.accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().get("signed")).isEqualTo(true);
        assertThat(mine.getBody().get("version")).isEqualTo(1);
        assertThat(mine.getBody().get("provider")).isEqualTo("SIMA_IMZA");
        // The hash, so that a creator can establish years later that the text in front of them
        // is the text they signed.
        assertThat(mine.getBody().get("documentHash")).isEqualTo(governingHash());
    }

    @Test
    @DisplayName("an account that has signed nothing says so rather than 404")
    void nothingSigned() {
        Account creator = creatorWithLegalSubject();

        ResponseEntity<Map<String, Object>> mine = rest.exchange(
                "/v1/me/agreements/CREATOR_AGREEMENT/signature",
                HttpMethod.GET,
                new HttpEntity<>(authorised(creator.accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().get("signed")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Map<String, Object> begin(Account creator) {
        ResponseEntity<Map<String, Object>> opened = beginRaw(creator);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        return opened.getBody();
    }

    private ResponseEntity<Map<String, Object>> beginRaw(Account creator) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("fin", FIN);
        body.put("mobile", "+994501112233");
        return rest.exchange(
                "/v1/me/agreements/CREATOR_AGREEMENT/signature",
                HttpMethod.POST,
                new HttpEntity<>(body, authorised(creator.accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> resolve(Account creator, String sessionId) {
        ResponseEntity<Map<String, Object>> resolved = resolveRaw(creator, sessionId);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resolved.getBody();
    }

    private ResponseEntity<Map<String, Object>> resolveRaw(Account creator, String sessionId) {
        return rest.exchange(
                "/v1/me/agreements/CREATOR_AGREEMENT/signature/sessions/" + sessionId,
                HttpMethod.GET,
                new HttpEntity<>(authorised(creator.accessToken())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private String governingHash() {
        return documents.findAll().stream()
                .filter(document -> document.getKind() == DocumentKind.CREATOR_AGREEMENT)
                .filter(document -> document.getLocale().equals(ReaderLocale.PRIMARY))
                .map(LegalDocument::getContentHash)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Publishes a version by writing the row.
     *
     * <p>The console's route needs a staff account and a two-step draft-then-publish, and this
     * suite is not about publishing — {@code LegalDocumentApiTests} is. What is written is what
     * a publication writes, including the content hash the domain computes, so the row is one
     * the application could have produced.
     */
    private void publish(AgreementKind kind, int version, String title, String body) {
        LegalDocument document = LegalDocument.draft(
                UUID.randomUUID(),
                DocumentKind.of(kind),
                ReaderLocale.PRIMARY,
                version,
                title,
                body,
                null,
                Instant.now());
        document.publish(null, Instant.now().minusSeconds(60), Instant.now());
        documents.save(document);
    }

    /**
     * Everything this suite wrote, in the order the foreign keys allow.
     *
     * <p>The trigger that makes a published version immutable refuses a {@code DELETE} of one,
     * which is the property V65 exists for; it is disabled around the delete and re-enabled
     * immediately, exactly as {@code LegalDocumentApiTests} and
     * {@code CreatorAgreementGateApiTests} do. A suite that left published rows behind would
     * put a creator agreement in force for every other suite in the run.
     *
     * <p>The signature is deleted before the session and the acceptance, because both point at
     * it with {@code RESTRICT} — deliberately, so that a signed agreement cannot silently
     * become a ticked one.
     */
    private void clearLegalRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM document_acceptances");
        jdbc.update("DELETE FROM signature_sessions");
        jdbc.update("DELETE FROM signatures");
        jdbc.execute("ALTER TABLE legal_documents DISABLE TRIGGER legal_documents_published_is_immutable");
        jdbc.update("DELETE FROM legal_documents");
        jdbc.execute("ALTER TABLE legal_documents ENABLE TRIGGER legal_documents_published_is_immutable");
    }

    private Account creatorWithLegalSubject() {
        Account creator = account();
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("subjectKind", "INDIVIDUAL");
        subject.put("legalName", CREATOR_NAME);
        ResponseEntity<String> saved = rest.exchange(
                "/v1/me/legal-subject",
                HttpMethod.PUT,
                new HttpEntity<>(subject, authorised(creator.accessToken())),
                String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        return creator;
    }

    private Account account() {
        EmailAddress email = EmailAddress.of("signing-%d@example.com".formatted(SEQUENCE.incrementAndGet()));
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Creator"),
                String.class);
        UUID id = users.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return new Account(
                tokens.issue(
                                id,
                                UUID.randomUUID(),
                                new AccessTokenIssuer.AccountStanding(true, false),
                                false,
                                Instant.now())
                        .value(),
                id);
    }

    private static HttpHeaders authorised(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private record Account(String accessToken, UUID id) {
    }
}
