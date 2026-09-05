package az.ideanest.signature.infrastructure;

import az.ideanest.signature.SignatureProperties;
import az.ideanest.signature.domain.CertificateSubject;
import az.ideanest.signature.domain.SignatureProvider;
import az.ideanest.signature.domain.SignatureProviderName;
import az.ideanest.signature.domain.SignatureProviderUnavailableException;
import az.ideanest.signature.domain.SignatureRequest;
import az.ideanest.signature.domain.SignatureResult;
import az.ideanest.signature.domain.SignatureSession;
import az.ideanest.signature.domain.StoredSignature;
import az.ideanest.signature.domain.Verification;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SİMA İmza, against its sandbox — issue #428.
 *
 * <h2>Registered only when a deployment asks for it</h2>
 *
 * <p>{@code @ConditionalOnProperty} rather than an unconditional bean, and the difference is
 * the whole safety argument. {@code PaymentProviders} ships no adapter at all because §9.2
 * refuses a stub that would make an unfinished path look finished; this is not a stub — it
 * speaks to a real service that issues real signatures against test certificates — so it is
 * switched off by configuration instead of by absence. The property is blank by default, and
 * {@code SignatureProviders} refuses outright to start against production SİMA until #423
 * answers.
 *
 * <h2>It is a translator and nothing else</h2>
 *
 * <p>One call in, one provider request out; one provider response in, one result out. It does
 * not decide whether the signer is the right person — #429 matches the FIN against the account
 * — it does not consult the database, and it does not retry. A hidden retry inside an adapter
 * is a second prompt on somebody's phone that nobody counted.
 *
 * <h2>What it drops on the floor, deliberately</h2>
 *
 * <p><strong>The certificate.</strong> SİMA returns the signer's certificate chain and this
 * adapter keeps the subject line and the two fields parsed out of it. #428 is explicit that no
 * copy of the citizen's certificate material is stored: that is V58's territory, with V58's
 * encryption and V58's retention sweep, and this must not become a second uncontrolled place
 * where a person's identity sits.
 *
 * <p><strong>The secret.</strong> Nothing here logs the client secret or the raw body, for
 * {@code ChargeResult}'s reason about {@code rawResponse}: a string that reaches a log is a
 * string that reaches a log aggregator, and a signing response carries a name and a FIN.
 *
 * <h2>The failure boundary</h2>
 *
 * <p>Every {@link RestClientException} becomes {@link SignatureProviderUnavailableException},
 * and no ordinary outcome does. A citizen who cancels is a {@code CANCELLED} value; a session
 * that ran out is {@code EXPIRED}; a SİMA that will not answer is a throw, because then the
 * platform does not know which of those happened. #428: the creator is told the signing service
 * is unavailable and that their draft is untouched.
 */
@Component
@ConditionalOnProperty(prefix = "ideanest.signature.provider", name = "primary", havingValue = "SIMA_IMZA")
public class SimaImzaSignatureProvider implements SignatureProvider {

    private static final Logger log = LoggerFactory.getLogger(SimaImzaSignatureProvider.class);

    private final RestClient http;
    private final SignatureProperties.Sima settings;
    private final Clock clock;

    public SimaImzaSignatureProvider(RestClient.Builder builder, SignatureProperties properties, Clock clock) {
        this.settings = properties.sima();
        this.clock = clock;
        if (!settings.isComplete()) {
            // A start-up failure rather than a first-call failure, for PaymentProviders'
            // reason: a deployment that believes it can take a legally binding signature and
            // cannot is a mistake discovered on the day somebody disputes one.
            throw new IllegalStateException(
                    "ideanest.signature.provider.primary is SIMA_IMZA and ideanest.signature.sima is"
                            + " missing its base URL, client id or client secret.");
        }
        this.http = builder.baseUrl(settings.baseUrl())
                .defaultHeader("X-Client-Id", settings.clientId())
                .defaultHeader("X-Client-Secret", settings.clientSecret())
                .build();
    }

    @Override
    public SignatureProviderName name() {
        return SignatureProviderName.SIMA_IMZA;
    }

    @Override
    public SignatureSession begin(SignatureRequest request) {
        BeginResponse answer = call(
                "begin",
                () -> http.post()
                        .uri("/sign/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "hash", request.documentHash(),
                                "hashAlgorithm", "SHA256",
                                "fin", request.signerFin(),
                                "phoneNumber", request.signerMobile(),
                                "displayText", request.purpose(),
                                "expiresInSeconds", settings.sessionLife().toSeconds()))
                        .retrieve()
                        .body(BeginResponse.class));

        if (answer == null || answer.sessionId() == null || answer.sessionId().isBlank()) {
            // An answer nobody can read is the same situation as no answer: the session may
            // exist, the citizen's phone may already be showing the prompt, and the platform
            // cannot say. That is a throw and not an outcome.
            throw new SignatureProviderUnavailableException(
                    SignatureProviderName.SIMA_IMZA, "SİMA began a session and did not say which.");
        }

        Instant expires = answer.expiresAt() == null
                ? clock.instant().plus(settings.sessionLife())
                : parseInstant(answer.expiresAt());

        return new SignatureSession(answer.sessionId(), answer.verificationCode(), expires);
    }

    @Override
    public SignatureResult resolve(String sessionId) {
        ResolveResponse answer = call(
                "resolve",
                () -> http.get()
                        .uri("/sign/sessions/{sessionId}", sessionId)
                        .retrieve()
                        .body(ResolveResponse.class));

        if (answer == null || answer.status() == null) {
            throw new SignatureProviderUnavailableException(
                    SignatureProviderName.SIMA_IMZA, "SİMA answered session %s with no status.".formatted(sessionId));
        }

        // The provider's vocabulary is mapped here and nowhere else. A caller that saw
        // "USER_CANCELLED" would be a caller a second provider breaks -- §9.4's rule, which
        // SignatureProviderBoundaryTests checks rather than restates.
        return switch (answer.status().toUpperCase(Locale.ROOT)) {
            case "PENDING", "IN_PROGRESS" -> SignatureResult.pending();
            case "CANCELLED", "USER_CANCELLED", "REJECTED" -> SignatureResult.cancelled(answer.detail());
            case "EXPIRED", "TIMEOUT" -> SignatureResult.expired(answer.detail());
            case "SIGNED", "COMPLETED" -> SignatureResult.signed(toSignature(sessionId, answer));
            default ->
                // A status this adapter has never seen. Not an outcome, because guessing which
                // of four it resembles is how a cancellation becomes a signature.
                throw new SignatureProviderUnavailableException(
                        SignatureProviderName.SIMA_IMZA,
                        "SİMA answered session %s with an unknown status %s.".formatted(sessionId, answer.status()));
        };
    }

    @Override
    public Verification verify(StoredSignature signature, String documentHash) {
        VerifyResponse answer = call(
                "verify",
                () -> http.post()
                        .uri("/sign/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "signature", signature.signatureValue(),
                                "hash", signature.documentHash(),
                                "hashAlgorithm", "SHA256",
                                "signedAt", signature.signedAt().toString()))
                        .retrieve()
                        .body(VerifyResponse.class));

        if (answer == null) {
            throw new SignatureProviderUnavailableException(
                    SignatureProviderName.SIMA_IMZA, "SİMA answered a verification with nothing.");
        }

        // The hash comparison is the platform's and not SİMA's, and that is on purpose. SİMA
        // can say the signature is sound over the bytes it was given; only the caller knows
        // which document those bytes were supposed to be. Asking the provider would be asking
        // it to confirm a fact it has no access to.
        boolean matches = signature.documentHash().equals(documentHash);

        // `detail` is never null on a Verification -- it is read by a log line and by nothing
        // else, and an Optional for a string that is almost always empty would be ceremony.
        String detail = answer.detail() == null ? "" : answer.detail();
        return new Verification(
                answer.valid(),
                matches,
                answer.certificateValidAtSigningTime(),
                matches ? detail : "The signature is over a different document.");
    }

    /**
     * Runs one call and turns every transport failure into the one exception a caller handles.
     *
     * <p>{@link RestClientException} covers a refused connection, a timeout, a 5xx and a body
     * that will not deserialise. All four are the same situation for a caller: the platform
     * does not know what the citizen did.
     */
    private <T> T call(String what, java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RestClientException e) {
            // The message and not the body, and no stack trace at this level: a SİMA response
            // carries a name and a FIN, and this line goes to an aggregator.
            log.warn("SİMA {} failed: {}", what, e.getMessage());
            throw new SignatureProviderUnavailableException(
                    SignatureProviderName.SIMA_IMZA, "SİMA could not be reached to " + what, e);
        }
    }

    private StoredSignature toSignature(String sessionId, ResolveResponse answer) {
        if (answer.signature() == null || answer.certificateSubject() == null) {
            throw new SignatureProviderUnavailableException(
                    SignatureProviderName.SIMA_IMZA,
                    "SİMA called session %s signed and returned no signature.".formatted(sessionId));
        }
        return new StoredSignature(
                SignatureProviderName.SIMA_IMZA,
                sessionId,
                answer.hash(),
                answer.signature(),
                new CertificateSubject(answer.certificateSubject(), answer.subjectName(), answer.subjectFin()),
                parseInstant(answer.signedAt()));
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            // A signing time that cannot be read is not a signature the platform may file: V67
            // makes `signed_at` NOT NULL because "when" is half of what the row asserts.
            throw new SignatureProviderUnavailableException(
                    SignatureProviderName.SIMA_IMZA, "SİMA returned a time nobody can read.", e);
        }
    }

    /**
     * SİMA's answers, as this adapter reads them.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} on every one, deliberately: a
     * provider adding a field must not take the platform down, and the fields that are read
     * are the fields that are named. The certificate chain is among the ones not named, which
     * is how it fails to be stored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BeginResponse(String sessionId, String verificationCode, String expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveResponse(
            String status,
            String detail,
            String signature,
            String hash,
            String certificateSubject,
            String subjectName,
            String subjectFin,
            String signedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerifyResponse(boolean valid, boolean certificateValidAtSigningTime, String detail) {
    }
}
