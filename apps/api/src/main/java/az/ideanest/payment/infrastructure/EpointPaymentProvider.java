package az.ideanest.payment.infrastructure;

import az.ideanest.payment.PaymentProperties;
import az.ideanest.payment.domain.ChargeResult;
import az.ideanest.payment.domain.PaymentEvent;
import az.ideanest.payment.domain.PaymentEventType;
import az.ideanest.payment.domain.PaymentProvider;
import az.ideanest.payment.domain.PayoutRequest;
import az.ideanest.payment.domain.PayoutResult;
import az.ideanest.payment.domain.ProviderCapabilities;
import az.ideanest.payment.domain.ProviderName;
import az.ideanest.payment.domain.ProviderOutcome;
import az.ideanest.payment.domain.ProviderUnavailableException;
import az.ideanest.payment.domain.RefundRequest;
import az.ideanest.payment.domain.RefundResult;
import az.ideanest.payment.domain.StoredCardChargeRequest;
import az.ideanest.payment.domain.TokenizationRequest;
import az.ideanest.payment.domain.TokenizationResult;
import az.ideanest.payment.domain.TokenizationSession;
import az.ideanest.payment.domain.WalletType;
import az.ideanest.payment.domain.WebhookVerificationException;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * §9.3's chosen provider, behind §9.4's interface (#433).
 *
 * <p>The first adapter this platform has ever shipped. Everything downstream — batched
 * collection (#64), §9.6's retry schedule, the circuit breaker, the ledger posting — has been
 * built and inert, held that way by {@code CollectionRun}'s refusal when no provider is
 * configured. This is what makes it move, and {@code docs/providers/epoint.md} is the record of
 * what Epoint was confirmed to be able to do before it was written.
 *
 * <h2>It registers only when a deployment has configured Epoint</h2>
 *
 * <p>{@code @ConditionalOnProperty} on {@code ideanest.payment.provider.primary}, so the
 * default everywhere — including every test — is still no adapter at all. §9.2's argument
 * against a stub is unchanged by the existence of a real one: a suite whose timer charges the
 * pledges it is about to charge itself is a flaky suite, and a demo environment that quietly
 * moves money is worse than one that refuses to.
 *
 * <h2>Two capabilities are refused at start-up, and that is deliberate</h2>
 *
 * <p>Epoint's specification version 1.0.3 does not address R-03 (scheme transaction chaining)
 * or R-08 (idempotency). {@code PaymentProperties.Epoint.Capabilities} defaults both to false,
 * {@code PaymentProviders} refuses an adapter that cannot do R-03, and this class refuses to
 * construct at all without R-08 — because R-08 is not on {@code ProviderCapabilities} and so
 * nothing else would check it. A deployment that has the written answers turns them on; one
 * that does not, does not start. §9.4 makes exactly this argument about the alternative:
 * discovering a missing capability at the first charge is discovering it at a campaign's close,
 * in front of every backer who has just been told it succeeded.
 *
 * <h2>Epoint's vocabulary stops here</h2>
 *
 * <p>{@code PaymentProviderBoundaryTests} checks it. Nothing outside this file knows that
 * Epoint signs with SHA-1, that its refund endpoint is called {@code reverse} while the
 * endpoint called {@code refund-request} is the payout, or that its statuses are Azerbaijani
 * words. A second provider is a second file.
 *
 * <h2>What is a value and what is a throw</h2>
 *
 * <p>§9.4's rule, and the whole of the money-safety argument: a decline is a
 * {@link ProviderOutcome#DECLINED} and an unreachable Epoint is a
 * {@link ProviderUnavailableException}. The platform knows nothing moved on the first and does
 * not know on the second, which is why only the second counts towards the circuit breaker and
 * why neither costs a backer one of §9.6's four attempts. Epoint's own {@code server_error} —
 * "status doğrulama xətası" — is mapped to the throw and not to a decline, because it is Epoint
 * saying it could not answer rather than a bank saying no.
 */
@Component
@ConditionalOnProperty(prefix = "ideanest.payment.provider", name = "primary", havingValue = "epoint")
public class EpointPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(EpointPaymentProvider.class);

    private static final ProviderName NAME = ProviderName.EPOINT;

    /**
     * How long an Epoint hosted card-entry page is treated as usable.
     *
     * <p>Epoint's specification does not state a session life, so this is the platform's own
     * bound rather than a transcription. It is short on purpose: a checkout resumed an hour
     * later is better told to start again than sent to a page that will refuse it without
     * saying why, which is {@code TokenizationSession#expiresAt}'s stated reason for existing.
     */
    private static final Duration SESSION_LIFE = Duration.ofMinutes(30);

    /** Epoint's approval code. Everything else on a refused answer is a decline reason. */
    private static final String APPROVED_CODE = "000";

    /**
     * The reader for everything Epoint says, and it exists for one field.
     *
     * <p>Jackson's default is to read a JSON floating-point number into a {@code double}, and
     * Epoint sends {@code amount} as a JSON number. A pledge that went out as {@code 120.50}
     * would come back through a binary float, and the platform's rule is that money is never a
     * double — {@code 0.1 + 0.2 != 0.3}, and here that is somebody's pledge and the amount a
     * ledger entry is posted for.
     *
     * <p>Its own mapper rather than the injected one, because this is a property of Epoint's
     * wire format and not of the application's: changing the application's mapper to suit one
     * provider would change how every controller reads every number.
     */
    private static final ObjectMapper EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RestClient http;
    private final PaymentProperties.Epoint settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final ProviderCapabilities capabilities;

    public EpointPaymentProvider(
            RestClient.Builder builder, PaymentProperties properties, ObjectMapper json, Clock clock) {
        this.settings = properties.epoint();
        this.json = json;
        this.clock = clock;

        if (!settings.isComplete()) {
            // A start-up failure and not a first-call one, for SimaImzaSignatureProvider's
            // reason: a deployment that believes it can collect and cannot is a mistake
            // discovered on the one day it must not be.
            throw new IllegalStateException("ideanest.payment.provider.primary is epoint and"
                    + " ideanest.payment.epoint is missing its base URL, public key or private key.");
        }
        if (!settings.capabilities().idempotentOrderId()) {
            // §9.3's R-08. Not on ProviderCapabilities, so PaymentProviders cannot check it and
            // this is the only place it can be refused. The difference it makes is a backer
            // charged once or twice for one pledge: the adapter sends its idempotency key as
            // Epoint's order_id, which is at-most-once only if Epoint refuses the duplicate,
            // and Epoint's specification does not say that it does.
            throw new IllegalStateException("Epoint's specification does not state what a repeated order_id does,"
                    + " and §9.3's R-08 is the difference between charging a backer once and twice."
                    + " Set ideanest.payment.epoint.capabilities.idempotent-order-id=true once the"
                    + " answer is in writing — see docs/providers/epoint.md.");
        }

        this.capabilities = capabilitiesFrom(settings.capabilities());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.requestTimeout());
        factory.setReadTimeout(settings.requestTimeout());

        this.http = builder.baseUrl(settings.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ProviderName name() {
        return NAME;
    }

    // ------------------------------------------------------------------
    // §9.2's phase one
    // ------------------------------------------------------------------

    /**
     * Register the backer's card with Epoint and get somewhere to send them.
     *
     * <p>Two endpoints, chosen by the amount, and the choice is §9.3's R-05 read literally —
     * "zero <em>or</em> minimal-value verification":
     *
     * <ul>
     *   <li>Zero: {@code /api/1/card-registration}. Epoint registers the card without charging
     *       anything, which is strictly better than a minimal-value authorisation because
     *       there is nothing to void afterwards — and §9.2's fourth step, the void, is not an
     *       operation Epoint offers.
     *   <li>Positive: {@code /api/1/card-registration-with-pay}, which registers and charges.
     *       <strong>The amount is charged and is not voided</strong>, so a deployment that
     *       configures one is deciding to take money from a backer at pledge time. That is a
     *       product decision and is recorded in {@code docs/providers/epoint.md} rather than
     *       hidden here.
     * </ul>
     *
     * <p>The session identifier is {@code orderId|cardId}, and the format belongs to this file.
     * Epoint's plain registration takes no {@code order_id} at all and hands the {@code card_id}
     * back at request time, while {@link #resolveTokenization} needs both — one to ask about
     * and one to return as the token. Nothing outside this adapter parses it;
     * {@code TokenizationSession#sessionId} is documented as the thing the adapter is later
     * asked about, and this is what this adapter needs to be asked.
     */
    @Override
    public TokenizationSession beginTokenization(TokenizationRequest request) {
        boolean charging = request.verificationAmount().isPositive();
        String orderId = request.idempotencyKey();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_key", settings.publicKey());
        payload.put("language", settings.language());
        payload.put("description", "IdeaNest card verification");
        payload.put("success_redirect_url", request.returnUrl().toString());
        payload.put("error_redirect_url", request.returnUrl().toString());
        if (charging) {
            payload.put("order_id", orderId);
            payload.put("amount", request.verificationAmount().amount());
            payload.put("currency", request.verificationAmount().currency());
        } else {
            // "Kart növü: 0 - ödəniş üçün kart; 1 - vəsaitlərin köçürülməsi üçün kart". A card
            // registered for collection is not a card registered as a payout destination, and
            // Epoint keeps the two apart. #432's destination is the other value.
            payload.put("refund", 0);
        }

        JsonNode answer = call(charging ? "/api/1/card-registration-with-pay" : "/api/1/card-registration", payload);

        String redirect = text(answer, "redirect_url");
        String cardId = text(answer, "card_id");
        if (redirect == null || cardId == null) {
            // An answer nobody can read is the same situation as no answer: the registration may
            // exist at Epoint and the platform cannot say which card it is. A throw, not an
            // outcome — see the class comment.
            throw new ProviderUnavailableException(
                    NAME, "Epoint began a card registration without a redirect url or a card id");
        }

        return new TokenizationSession(
                orderId + "|" + cardId, URI.create(redirect), clock.instant().plus(SESSION_LIFE));
    }

    @Override
    public TokenizationResult resolveTokenization(String sessionId) {
        int separator = sessionId == null ? -1 : sessionId.indexOf('|');
        if (separator < 0) {
            throw new ProviderUnavailableException(
                    NAME, "A tokenisation session identifier this adapter did not mint cannot be resolved");
        }
        String orderId = sessionId.substring(0, separator);
        String cardId = sessionId.substring(separator + 1);

        JsonNode answer = call(
                "/api/1/get-status",
                Map.of("public_key", settings.publicKey(), "language", settings.language(), "order_id", orderId));

        ProviderOutcome outcome = outcomeOf(answer, "resolve a card registration");
        if (outcome != ProviderOutcome.APPROVED) {
            return new TokenizationResult(
                    outcome,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    outcome == ProviderOutcome.DECLINED ? failureCode(answer) : null,
                    text(answer, "message"));
        }

        // §9.3's R-03 is the row Epoint's specification does not address, and this is the
        // consequence in code: the RRN of the registration is the only scheme-side identifier
        // Epoint exposes, so it is what the later merchant-initiated charge is chained to. If
        // Epoint's written answer is that the RRN is not that identifier, the fix is to set
        // capabilities.scheme-chaining=false, which stops the service — see the class comment.
        String rrn = text(answer, "rrn");
        if (rrn == null) {
            throw new ProviderUnavailableException(
                    NAME, "Epoint approved a card registration without an RRN, which §9.3's R-03 is carried on");
        }

        String mask = text(answer, "card_mask");
        return new TokenizationResult(
                ProviderOutcome.APPROVED, cardId, rrn, null, lastFourOf(mask), null, null, null, null);
    }

    // ------------------------------------------------------------------
    // §9.2's phase two
    // ------------------------------------------------------------------

    @Override
    public ChargeResult chargeStoredCard(StoredCardChargeRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_key", settings.publicKey());
        payload.put("language", settings.language());
        payload.put("card_id", request.card().token());
        // R-08 through Epoint's only unique field. The key is per attempt, so §9.6's second
        // attempt is a different order and a repeat of the first is the same one.
        payload.put("order_id", request.idempotencyKey());
        payload.put("amount", request.amount().amount());
        payload.put("currency", request.amount().currency());
        payload.put("description", request.statementDescriptor());

        JsonNode answer = call("/api/1/execute-pay", payload);
        ProviderOutcome outcome = outcomeOf(answer, "charge a stored card");

        return new ChargeResult(
                outcome,
                text(answer, "transaction"),
                outcome == ProviderOutcome.DECLINED ? failureCode(answer) : null,
                outcome == ProviderOutcome.DECLINED ? text(answer, "message") : null,
                redacted(answer));
    }

    /**
     * §9.7's reversal — Epoint's {@code /api/1/reverse}, and <strong>not</strong> its
     * {@code /api/1/refund-request}.
     *
     * <p>The naming is Epoint's and it is the wrong way round from every intuition: the
     * endpoint called "refund-request" is headed "Vəsaitlərin köçürülməsi sorğusu" and sends
     * money <em>to</em> a card, which is {@link #payout}. The one that reverses a charge is
     * "Əməliyyatın ləğvi sorğusu", {@code reverse}. Sending a refund to the first would credit
     * a card the platform chose rather than reversing the charge the backer made.
     *
     * <p>{@code amount} is optional there and is always sent, which is R-06's partial: Epoint
     * documents "Məbləğin qismən qaytarılması göstərilə bilər". The caller is what checks
     * whether a partial is allowed at all; an adapter whose {@code partialRefund} is false may
     * assume it is only handed the full amount.
     */
    @Override
    public RefundResult refund(RefundRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_key", settings.publicKey());
        payload.put("language", settings.language());
        payload.put("transaction", request.providerTransactionId());
        payload.put("amount", request.amount().amount());
        payload.put("currency", request.amount().currency());

        JsonNode answer = call("/api/1/reverse", payload);
        ProviderOutcome outcome = outcomeOf(answer, "reverse a charge");

        return new RefundResult(
                outcome,
                text(answer, "transaction"),
                outcome == ProviderOutcome.DECLINED ? failureCode(answer) : null,
                outcome == ProviderOutcome.DECLINED ? text(answer, "message") : null,
                redacted(answer));
    }

    // ------------------------------------------------------------------
    // §9.5's last arrow
    // ------------------------------------------------------------------

    /**
     * Send the creator their net — Epoint's {@code /api/1/refund-request} (#435).
     *
     * <p><strong>A transfer at payout, and not a split at collection.</strong> Epoint's
     * {@code /api/1/split-*} endpoints exist, but #422's four questions about them — how a
     * creator is onboarded as an Epoint user, when the split executes, who is the merchant of
     * record, and who a chargeback debits — have no answers, and the last of those decides
     * #71. §9.5 therefore stands as drawn: collect to the platform, hold for fourteen days,
     * transfer. Changing that is a change to the ledger, to the hold and possibly to the
     * platform's regulatory position, and it gets its own epic rather than arriving inside an
     * adapter.
     *
     * <p>{@code destinationReference} is the {@code card_id} of a registration Epoint made with
     * {@code refund=1} — a card registered for transfers out, which is a different kind of
     * registration from the one a collection charges. It comes from #432's creator-supplied,
     * verified destination and is opaque here.
     */
    @Override
    public PayoutResult payout(PayoutRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("public_key", settings.publicKey());
        payload.put("language", settings.language());
        payload.put("card_id", request.destinationReference());
        payload.put("order_id", request.idempotencyKey());
        payload.put("amount", request.amount().amount());
        payload.put("currency", request.amount().currency());
        payload.put("description", "IdeaNest payout " + request.payoutId());

        JsonNode answer = call("/api/1/refund-request", payload);
        ProviderOutcome outcome = outcomeOf(answer, "send a payout");

        return new PayoutResult(
                outcome,
                text(answer, "transaction"),
                outcome == ProviderOutcome.DECLINED ? failureCode(answer) : null,
                outcome == ProviderOutcome.DECLINED ? text(answer, "message") : null,
                redacted(answer));
    }

    // ------------------------------------------------------------------
    // §9.3's R-07
    // ------------------------------------------------------------------

    /**
     * Verify Epoint's callback and normalise it (#434).
     *
     * <p><strong>The signature is in the body, not in a header.</strong> Epoint POSTs two form
     * fields, {@code data} and {@code signature}, and the signature is over the {@code data}
     * field's value exactly as it was sent. So the order here is: pull the two fields out of
     * the raw bytes, verify, and only then base64-decode and parse. A body that has been
     * through a JSON parser and back is a different sequence of bytes and a signature over the
     * re-serialised version verifies nothing — which is why {@code headers} is unused and the
     * raw bytes are the input.
     *
     * <p><strong>Nothing carries a signed timestamp.</strong> §17.2's replay window is driven
     * from {@code PaymentEvent#signedAt}, and Epoint signs no time, so this returns null and
     * {@code ProviderWebhooks} skips the window. The only replay control for Epoint is
     * {@code provider_webhook_events}' unique index over {@code (provider, provider_event_id)},
     * which is durable and survives a restart. That is a finding for #422 and recorded in
     * {@code docs/providers/epoint.md}; a timestamp this adapter invented would be a window
     * that checked the platform's own clock against itself.
     */
    @Override
    public PaymentEvent parseWebhook(byte[] rawBody, Map<String, String> headers) {
        Map<String, String> form = formFieldsOf(rawBody);
        String data = form.get("data");
        String signature = form.get("signature");
        if (data == null || signature == null) {
            throw new WebhookVerificationException(NAME, "An Epoint delivery carries a data and a signature field");
        }
        if (!EpointSignature.verify(settings.privateKey(), data, signature)) {
            // A refusal and never a best-effort accept. This endpoint is unauthenticated by
            // construction and the signature is the entire authentication.
            throw new WebhookVerificationException(NAME, "An Epoint delivery's signature did not verify");
        }

        JsonNode body;
        String decoded;
        try {
            decoded = EpointSignature.decode(data);
            body = EXACT.readTree(decoded);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new WebhookVerificationException(NAME, "An Epoint delivery's data field could not be read", e);
        }

        String orderId = text(body, "order_id");
        String transaction = text(body, "transaction");
        String status = text(body, "status");
        String reference = transaction != null ? transaction : orderId;
        if (reference == null) {
            throw new WebhookVerificationException(
                    NAME, "An Epoint delivery with neither a transaction nor an order id cannot be processed once");
        }

        // The deduplication identity, and it is deliberately the operation AND its outcome.
        //
        // Epoint sends no event id of its own, so the operation is the closest thing: its
        // `transaction`, which a redelivery repeats and which `order_id` cannot stand in for
        // because §9.6 makes several attempts against one pledge and each is a different order.
        //
        // The status is the other half, and leaving it out is a silent bug rather than an
        // inelegance: a charge that settles and is later returned produces two deliveries about
        // ONE transaction, and an identity that named only the transaction would make the
        // second look like a redelivery of the first. V43's unique index would then swallow the
        // refund — the platform would have been told the money went back and would have
        // recorded nothing.
        String identity = status == null ? reference : reference + ":" + status.toLowerCase(Locale.ROOT);

        return new PaymentEvent(
                NAME,
                identity,
                typeOf(body),
                transaction,
                amountOf(body),
                null,
                decoded);
    }

    @Override
    public ProviderCapabilities capabilities() {
        return capabilities;
    }

    // ------------------------------------------------------------------
    // Epoint's vocabulary, and it goes no further than this file
    // ------------------------------------------------------------------

    /**
     * One Epoint call: sign the payload, post it as a form, read the answer.
     *
     * <p>Every endpoint takes the same two fields. {@code data} is the base64 of the JSON and
     * {@code signature} is over that exact string, so the JSON is serialised once and the same
     * bytes are signed and sent — serialising twice is how a signature stops matching a body
     * that differs only in key order.
     */
    private JsonNode call(String path, Map<String, Object> payload) {
        String data = EpointSignature.encode(json.writeValueAsString(payload));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", data);
        form.add("signature", EpointSignature.sign(settings.privateKey(), data));

        String answer;
        try {
            answer = http.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // The message and not the body, and no stack trace at this level: an Epoint answer
            // carries a cardholder's name, and this line goes to an aggregator.
            log.warn("Epoint call to {} failed: {}", path, e.getMessage());
            throw new ProviderUnavailableException(NAME, "Epoint could not be reached at " + path, e);
        }

        if (answer == null || answer.isBlank()) {
            throw new ProviderUnavailableException(NAME, "Epoint answered " + path + " with nothing");
        }
        try {
            return EXACT.readTree(answer);
        } catch (JacksonException e) {
            throw new ProviderUnavailableException(NAME, "Epoint answered " + path + " with something unreadable", e);
        }
    }

    /**
     * Epoint's {@code status} onto §9.4's three outcomes.
     *
     * <p>Three and never four. {@code server_error} is Epoint saying it could not answer, which
     * is the situation the platform must not confuse with a bank saying no — a decline costs a
     * backer one of §9.6's attempts and an unreachable provider does not.
     */
    private ProviderOutcome outcomeOf(JsonNode answer, String what) {
        String status = text(answer, "status");
        if (status == null) {
            throw new ProviderUnavailableException(NAME, "Epoint answered a request to " + what + " with no status");
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "success" -> ProviderOutcome.APPROVED;
            // "error" is the specification's word and "failed" is the one the callback uses in
            // practice. Both are the bank or Epoint refusing, and both are a value.
            case "error", "failed" -> ProviderOutcome.DECLINED;
            // "new" is Epoint's "qeydə alınıb": it has the instruction and has not decided.
            case "new", "pending" -> ProviderOutcome.PENDING;
            case "server_error" -> throw new ProviderUnavailableException(
                    NAME, "Epoint could not answer a request to " + what);
            default -> throw new ProviderUnavailableException(
                    NAME, "Epoint answered a request to " + what + " with an unknown status " + status);
        };
    }

    /**
     * Why it was refused, short enough for {@code transactions.failure_code}.
     *
     * <p>Epoint's {@code code} is the bank's own response code — {@code 000} approved,
     * {@code 1xx} decline, {@code 2xx} pick-up, {@code 9xx} a system refusal — and it is passed
     * through unchanged, because a code the platform renamed is a code nobody can look up in
     * Epoint's own table. A refusal that arrives with no code at all still gets one, since
     * {@code ChargeResult} refuses a decline without one and "the provider said no and did not
     * say why" is itself the fact worth recording.
     */
    private String failureCode(JsonNode answer) {
        String code = text(answer, "code");
        if (code == null || code.isBlank() || APPROVED_CODE.equals(code)) {
            return "epoint_refused";
        }
        return code;
    }

    /**
     * Epoint's callback onto {@code PaymentEventType}.
     *
     * <p>An event this adapter does not recognise becomes {@link PaymentEventType#UNRECOGNISED},
     * which {@code ProviderWebhooks} stores and ignores. Epoint adding a status must not start
     * failing deliveries — a 500 makes Epoint retry something nobody wants.
     *
     * <p><strong>There is no chargeback case, and its absence is the finding.</strong> Epoint's
     * specification documents five payment statuses and none of them is a dispute. #68 built
     * {@code Dispute}, {@code DisputeState} and {@code DisputeService} and, on this
     * specification, nothing can ever open one. Inventing an event name here would be worse
     * than the gap: it would look wired.
     */
    private PaymentEventType typeOf(JsonNode body) {
        String status = text(body, "status");
        String operation = text(body, "operation_code");
        if (status == null) {
            return PaymentEventType.UNRECOGNISED;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            // Epoint's operation_code: 001 is a card registration, 200 a registration that
            // charged, 100 a customer payment. Only the last is §9.2's phase two. Routing
            // either of the first two to CHARGE_SUCCEEDED would settle a pledge on the strength
            // of a card being saved — a campaign told it collected money nobody was charged.
            case "success" -> "001".equals(operation) || "200".equals(operation)
                    ? PaymentEventType.UNRECOGNISED
                    : PaymentEventType.CHARGE_SUCCEEDED;
            case "failed", "error" -> PaymentEventType.CHARGE_FAILED;
            case "returned" -> PaymentEventType.REFUND_SUCCEEDED;
            default -> PaymentEventType.UNRECOGNISED;
        };
    }

    /**
     * The delivery's amount, when it carries one.
     *
     * <p>Read as a {@code BigDecimal} from the JSON tree and never through a {@code double}.
     * Epoint sends {@code amount} as a JSON number, and {@code 20.50} through a binary float is
     * not {@code 20.50} — on a funding platform that is somebody's pledge. AZN is the only
     * currency Epoint settles in (R-11) and the callback does not repeat it, so it is named
     * here rather than guessed from an absent field.
     */
    private Money amountOf(JsonNode body) {
        JsonNode amount = body.get("amount");
        if (amount == null || amount.isNull()) {
            return null;
        }
        BigDecimal value = amount.isNumber() ? amount.decimalValue() : new BigDecimal(amount.asString().trim());
        return Money.of(value, "AZN");
    }

    /**
     * An Epoint answer, with what §17.2 does not allow to be stored removed.
     *
     * <p>{@code rawResponse} is written to {@code transactions.provider_response} and read in
     * support conversations, and Epoint puts two things in every answer that must not survive
     * that: {@code card_name}, which is a person's name, and {@code card_mask}, which is
     * {@code 123456******1234} — the first six digits and the last four. §17.2 permits the last
     * four and nothing more, so the mask is reduced rather than deleted, because "which card
     * was this" is the actual support question.
     *
     * <p>Done here and not afterwards: once this string is returned it is stored and logged, and
     * a redaction applied later is a redaction applied to a copy.
     */
    private String redacted(JsonNode answer) {
        if (!answer.isObject()) {
            return answer.toString();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : answer.properties()) {
            String key = field.getKey();
            if ("card_name".equals(key)) {
                continue;
            }
            if ("card_mask".equals(key)) {
                String last4 = lastFourOf(field.getValue().asString());
                if (last4 != null) {
                    copy.put("card_last4", last4);
                }
                continue;
            }
            copy.put(key, field.getValue());
        }
        return json.writeValueAsString(copy);
    }

    /** {@code 123456******1234} to {@code 1234}, and null for anything that is not one. */
    private static String lastFourOf(String mask) {
        if (mask == null || mask.length() < 4) {
            return null;
        }
        String tail = mask.substring(mask.length() - 4);
        return tail.matches("[0-9]{4}") ? tail : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String rendered = value.isValueNode() ? value.asString() : value.toString();
        return rendered.isBlank() ? null : rendered;
    }

    /**
     * The {@code data} and {@code signature} fields, out of the raw bytes Epoint posted.
     *
     * <p>Epoint's specification says {@code application/x-www-form-urlencoded} and the
     * integration this was read against sends {@code multipart/form-data}, so both are handled.
     * Parsing it here rather than letting the servlet container bind parameters is the whole
     * point: {@code +} means a space in a form encoding and means itself inside base64, and a
     * container that decoded the field and re-encoded it would hand over a different string
     * from the one that was signed.
     */
    private static Map<String, String> formFieldsOf(byte[] rawBody) {
        String body = new String(rawBody, StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();

        if (body.startsWith("--")) {
            // multipart. Each part is a boundary, headers, a blank line, then the value.
            String[] parts = body.split("\r\n--|\n--");
            for (String part : parts) {
                int nameAt = part.indexOf("name=\"");
                int blank = part.indexOf("\r\n\r\n");
                int blankLength = 4;
                if (blank < 0) {
                    blank = part.indexOf("\n\n");
                    blankLength = 2;
                }
                if (nameAt < 0 || blank < 0) {
                    continue;
                }
                int nameEnd = part.indexOf('"', nameAt + 6);
                if (nameEnd < 0) {
                    continue;
                }
                fields.put(part.substring(nameAt + 6, nameEnd), part.substring(blank + blankLength).trim());
            }
            return fields;
        }

        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            fields.put(
                    URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    /**
     * §9.3's fourteen rows as this deployment confirmed them, in {@code ProviderCapabilities}'
     * shape.
     *
     * <p>Read once at construction rather than on every call: {@code PaymentProviders} asserts
     * the three required rows at start-up, and a capability that could change afterwards would
     * be a capability that passed the assertion and then did not hold.
     */
    private static ProviderCapabilities capabilitiesFrom(PaymentProperties.Epoint.Capabilities confirmed) {
        Set<WalletType> wallets = new HashSet<>();
        for (String wallet : confirmed.wallets()) {
            for (WalletType candidate : WalletType.values()) {
                if (candidate.name().equalsIgnoreCase(wallet.trim())) {
                    wallets.add(candidate);
                }
            }
        }
        Set<String> currencies = new HashSet<>();
        for (String currency : confirmed.currencies()) {
            currencies.add(currency.trim().toUpperCase(Locale.ROOT));
        }

        return new ProviderCapabilities(
                confirmed.cardOnFile(),
                confirmed.merchantInitiated(),
                confirmed.preAuthHoldDays(),
                confirmed.schemeChaining(),
                confirmed.splitPayment(),
                confirmed.partialRefund(),
                wallets,
                currencies);
    }
}
