package az.ideanest.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.payment.domain.ChargeResult;
import az.ideanest.payment.domain.PaymentEvent;
import az.ideanest.payment.domain.PaymentEventType;
import az.ideanest.payment.domain.PayoutRequest;
import az.ideanest.payment.domain.PayoutResult;
import az.ideanest.payment.domain.ProviderName;
import az.ideanest.payment.domain.ProviderOutcome;
import az.ideanest.payment.domain.ProviderUnavailableException;
import az.ideanest.payment.domain.RefundRequest;
import az.ideanest.payment.domain.RefundResult;
import az.ideanest.payment.domain.StoredCard;
import az.ideanest.payment.domain.StoredCardChargeRequest;
import az.ideanest.payment.domain.TokenizationRequest;
import az.ideanest.payment.domain.TokenizationResult;
import az.ideanest.payment.domain.TokenizationSession;
import az.ideanest.payment.domain.WalletType;
import az.ideanest.payment.domain.WebhookVerificationException;
import az.ideanest.payment.infrastructure.EpointPaymentProvider;
import az.ideanest.shared.money.Money;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Epoint adapter — issues #433, #434 and #435.
 *
 * <p>Against a stub HTTP server rather than against Epoint. #422 records that Epoint's
 * specification does not offer a sandbox (R-14), so there is nothing to run against; and even
 * with one, a suite that called somebody else's service would fail for reasons that are not
 * ours, on their schedule, and could not produce the cases that matter — a decline, a provider
 * that cannot answer, and a delivery signed with the wrong key.
 *
 * <p>A stub server and not {@code MockRestServiceServer}, unlike {@code SimaImzaSignatureProvider}'s
 * suite, for two reasons that are specific to this adapter. The adapter sets a read timeout on
 * its own request factory, which a mock server's factory would have to replace; and half of
 * what is under test is <strong>what goes on the wire</strong> — the form encoding, the base64,
 * and a SHA-1 over the exact bytes of a concatenation. A test that never encoded anything would
 * assert that the adapter calls its own helper.
 *
 * <p>The assertions are §9.4's distinctions, one test each: a decline is a value, an
 * unreachable provider is a throw, Epoint's {@code server_error} is the second and not the
 * first, and a refund goes to the endpoint that reverses a charge rather than to the one whose
 * name says refund and which sends money to a card.
 */
class EpointPaymentProviderTests {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String PUBLIC_KEY = "i000000001";
    private static final String PRIVATE_KEY = "d3hjsl38sd8kdfhbcea0be04eafde9e8e2bad2fb092d";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WireMockServer server;

    @BeforeAll
    static void startEpoint() {
        // HTTP/1.1 only, following SimaImzaStub: WireMock's Jetty answers an h2c upgrade and
        // then cancels the stream, which the adapter reports as a provider it could not reach.
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterAll
    static void stopEpoint() {
        server.stop();
    }

    @BeforeEach
    void forgetPreviousStubs() {
        server.resetAll();
    }

    // ------------------------------------------------------------------
    // Start-up: the two rows Epoint's specification does not answer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R-08 is unconfirmed by default, and the adapter refuses to exist")
    void refusesWithoutConfirmedIdempotency() {
        assertThatThrownBy(() -> adapterWith(PaymentProperties.Epoint.Capabilities.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order_id")
                .hasMessageContaining("idempotent-order-id");
    }

    @Test
    @DisplayName("a deployment that names Epoint and configures no credentials does not start")
    void refusesWithoutCredentials() {
        PaymentProperties.Epoint blank = new PaymentProperties.Epoint(
                "", "", "", "", "az", Duration.ofSeconds(5), confirmed());

        assertThatThrownBy(() -> new EpointPaymentProvider(
                        RestClient.builder(), propertiesOf(blank), JSON, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ideanest.payment.epoint");
    }

    /**
     * R-03 is the row that stops the service, and this is where that is visible.
     *
     * <p>The adapter constructs — R-03 is not its own gate — and reports a capability set
     * {@code PaymentProviders} refuses at start-up. The refusal itself is
     * {@code ProviderRegistryTests}'; what is asserted here is that the adapter tells the truth
     * about what #422 did and did not obtain.
     */
    @Test
    @DisplayName("without R-03 in writing the adapter reports a capability set §9.4 refuses")
    void reportsWhatWasActuallyConfirmed() {
        EpointPaymentProvider adapter = adapterWith(new PaymentProperties.Epoint.Capabilities(
                true, true, false, true, false, true, null, List.of("AZN"), List.of("APPLE_PAY")));

        assertThat(adapter.capabilities().supportsStoredCardCollection()).isFalse();
        assertThat(adapter.capabilities().missing()).containsExactly("R-03 scheme transaction chaining");
    }

    @Test
    @DisplayName("the confirmed answers become the capability record")
    void capabilitiesComeFromConfiguration() {
        EpointPaymentProvider adapter = adapter();

        assertThat(adapter.name()).isEqualTo(ProviderName.EPOINT);
        assertThat(adapter.capabilities().supportsStoredCardCollection()).isTrue();
        assertThat(adapter.capabilities().partialRefund()).isTrue();
        // R-10's mechanism exists and its four questions do not have answers — see
        // docs/providers/epoint.md. False is the record of that, not an oversight.
        assertThat(adapter.capabilities().splitPayment()).isFalse();
        assertThat(adapter.capabilities().preAuthHoldDays()).isNull();
        assertThat(adapter.capabilities().currencies()).containsExactly("AZN");
        assertThat(adapter.capabilities().wallets()).containsExactlyInAnyOrder(WalletType.APPLE_PAY, WalletType.GOOGLE_PAY);
    }

    // ------------------------------------------------------------------
    // §9.2's phase two
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an approved charge is an approval carrying Epoint's transaction")
    void chargeApproved() {
        epointAnswers("/api/1/execute-pay", """
                {"status":"success","code":"000","transaction":"te001111111","rrn":"512345678901"}
                """);

        ChargeResult result = adapter().chargeStoredCard(charge(Money.of(new BigDecimal("120.50"), "AZN")));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.APPROVED);
        assertThat(result.providerTransactionId()).isEqualTo("te001111111");
        assertThat(result.failureCode()).isNull();
    }

    /**
     * §9.6 puts collection failure at 5–15% of pledges, so this is the ordinary Tuesday and
     * never an exception. The code that comes back is the bank's own, unchanged, because a code
     * the platform renamed is a code nobody can look up in Epoint's table.
     */
    @Test
    @DisplayName("a decline is a value carrying the bank's own response code")
    void chargeDeclined() {
        epointAnswers("/api/1/execute-pay", """
                {"status":"failed","code":"116","message":"imtina, kifayet qeder vesait yoxdur",
                 "transaction":"te001111112"}
                """);

        ChargeResult result = adapter().chargeStoredCard(charge(Money.of(new BigDecimal("40.00"), "AZN")));

        assertThat(result.isDeclined()).isTrue();
        assertThat(result.failureCode()).isEqualTo("116");
        assertThat(result.providerTransactionId()).isEqualTo("te001111112");
    }

    /**
     * Epoint's own words: {@code server_error} is "status doğrulama xətası" — it could not
     * answer. The platform then does not know whether anything moved, which is a different
     * situation from knowing that nothing did, and §9.4 keeps them apart because only one of
     * them costs a backer an attempt.
     */
    @Test
    @DisplayName("server_error is an unreachable provider and not a decline")
    void serverErrorIsNotADecline() {
        epointAnswers("/api/1/execute-pay", """
                {"status":"server_error"}
                """);

        assertThatThrownBy(() -> adapter().chargeStoredCard(charge(Money.of(new BigDecimal("10.00"), "AZN"))))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    @DisplayName("an Epoint that answers 500 is unreachable, not a decline")
    void transportFailureIsAThrow() {
        server.stubFor(WireMock.post(WireMock.urlEqualTo("/api/1/execute-pay"))
                .willReturn(WireMock.serverError()));

        assertThatThrownBy(() -> adapter().chargeStoredCard(charge(Money.of(new BigDecimal("10.00"), "AZN"))))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("/api/1/execute-pay");
    }

    @Test
    @DisplayName("an answer with a status this adapter has never seen is unreachable, not a guess")
    void unknownStatusIsAThrow() {
        epointAnswers("/api/1/execute-pay", """
                {"status":"in_progress_maybe"}
                """);

        assertThatThrownBy(() -> adapter().chargeStoredCard(charge(Money.of(new BigDecimal("10.00"), "AZN"))))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("in_progress_maybe");
    }

    /**
     * §9.3's R-08 on the wire.
     *
     * <p>The idempotency key is Epoint's {@code order_id} and nothing else is, because it is
     * the only field Epoint documents as unique. The key is per attempt, so §9.6's second
     * attempt is a different order and a repeat of a request whose answer was lost is the same
     * one — which is the difference between charging a backer once and twice.
     */
    @Test
    @DisplayName("the idempotency key travels as Epoint's order_id, and the amount exactly")
    void theRequestCarriesTheKeyAndTheAmount() {
        epointAnswers("/api/1/execute-pay", """
                {"status":"success","code":"000","transaction":"te1"}
                """);

        adapter().chargeStoredCard(charge(Money.of(new BigDecimal("120.50"), "AZN")));

        JsonNode sent = whatWasSentTo("/api/1/execute-pay");
        assertThat(sent.get("order_id").asString()).isEqualTo("collection-key-1");
        assertThat(sent.get("card_id").asString()).isEqualTo("card-token-1");
        assertThat(sent.get("public_key").asString()).isEqualTo(PUBLIC_KEY);
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("120.50"));
        assertThat(sent.get("currency").asString()).isEqualTo("AZN");

        // The bytes and not the parsed node, which is the only way to see this: a JSON number
        // read back through Jackson's default double would compare equal whatever the wire
        // said. Money crosses this boundary as BigDecimal at the currency's own scale, because
        // 0.1 + 0.2 != 0.3 and here that is somebody's pledge.
        assertThat(jsonSentTo("/api/1/execute-pay")).contains("\"amount\":120.50");
    }

    /**
     * §17.2 targets SAQ A, and {@code rawResponse} is written to
     * {@code transactions.provider_response} and read in support conversations.
     *
     * <p>Epoint puts two things in every answer that must not survive that: a cardholder's name,
     * and a mask that is the first six digits as well as the last four. The name goes; the mask
     * is reduced rather than deleted, because "which card was this" is the actual support
     * question.
     */
    @Test
    @DisplayName("the stored answer keeps the last four digits and neither the name nor the BIN")
    void theRawResponseIsRedacted() {
        epointAnswers("/api/1/execute-pay", """
                {"status":"success","code":"000","transaction":"te1",
                 "card_name":"ISMET CAHANGIROV","card_mask":"412345******9012"}
                """);

        ChargeResult result = adapter().chargeStoredCard(charge(Money.of(new BigDecimal("10.00"), "AZN")));

        assertThat(result.rawResponse())
                .doesNotContain("ISMET")
                .doesNotContain("412345")
                .contains("\"card_last4\":\"9012\"")
                .contains("te1");
    }

    // ------------------------------------------------------------------
    // §9.7's reversal, and the endpoint it is not
    // ------------------------------------------------------------------

    /**
     * The naming trap, asserted rather than described.
     *
     * <p>Epoint's {@code /api/1/refund-request} is headed "Vəsaitlərin köçürülməsi sorğusu" and
     * sends money <em>to</em> a card; the endpoint that reverses a charge is
     * {@code /api/1/reverse}. Sending a refund to the first would credit a card the platform
     * chose instead of reversing the charge the backer made, and both calls would answer
     * {@code success}.
     */
    @Test
    @DisplayName("a refund reverses the charge and does not transfer money to a card")
    void refundGoesToReverse() {
        epointAnswers("/api/1/reverse", """
                {"status":"success","transaction":"te-reversal-1"}
                """);

        RefundResult result = adapter()
                .refund(new RefundRequest(
                        UUID.randomUUID(), "te001111111", Money.of(new BigDecimal("25.00"), "AZN"), "cancelled", "refund-key-1"));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.APPROVED);
        server.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/1/reverse")));
        assertThat(server.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/1/refund-request")))).isEmpty();

        // R-06's partial. Epoint documents amount as optional on reverse — "Məbləğin qismən
        // qaytarılması göstərilə bilər" — and the adapter always sends it, so a partial refund
        // is a partial refund rather than a full one nobody noticed.
        JsonNode sent = whatWasSentTo("/api/1/reverse");
        assertThat(sent.get("transaction").asString()).isEqualTo("te001111111");
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    // ------------------------------------------------------------------
    // §9.5's last arrow (#435)
    // ------------------------------------------------------------------

    /**
     * §9.5 as drawn: a transfer at payout, to a destination the creator supplied.
     *
     * <p>Epoint's {@code split-*} endpoints are not used and #422 records why — the four
     * questions about sub-merchant onboarding, when a split executes, who is the merchant of
     * record and who a chargeback debits have no answers, and the last decides #71.
     */
    @Test
    @DisplayName("a payout transfers to the creator's registered card and nothing is split")
    void payoutTransfersToTheDestination() {
        epointAnswers("/api/1/refund-request", """
                {"status":"success","transaction":"te-payout-1"}
                """);

        PayoutResult result = adapter()
                .payout(new PayoutRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Money.of(new BigDecimal("4500.00"), "AZN"),
                        "destination-card-1",
                        "payout-key-1"));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.APPROVED);
        assertThat(result.providerTransactionId()).isEqualTo("te-payout-1");
        assertThat(server.findAll(WireMock.postRequestedFor(WireMock.urlPathMatching("/api/1/split.*")))).isEmpty();

        JsonNode sent = whatWasSentTo("/api/1/refund-request");
        assertThat(sent.get("card_id").asString()).isEqualTo("destination-card-1");
        assertThat(sent.get("order_id").asString()).isEqualTo("payout-key-1");
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("4500.00"));
    }

    /**
     * "A bank transfer settles on a banking day, and the platform's obligation is discharged
     * when the instruction is taken" — {@code PayoutGateway}'s words. Epoint's {@code new} is
     * that: it has the instruction and has not decided.
     */
    @Test
    @DisplayName("a payout Epoint has taken and not decided is pending, not approved")
    void payoutTakenAndUndecidedIsPending() {
        epointAnswers("/api/1/refund-request", """
                {"status":"new","transaction":"te-payout-2"}
                """);

        PayoutResult result = adapter()
                .payout(new PayoutRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Money.of(new BigDecimal("10.00"), "AZN"),
                        "destination-card-1",
                        "payout-key-2"));

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.PENDING);
        assertThat(result.failureCode()).isNull();
    }

    // ------------------------------------------------------------------
    // §9.2's phase one
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a zero-amount verification registers the card without charging anything")
    void tokenisationWithoutACharge() {
        epointAnswers("/api/1/card-registration", """
                {"status":"success","redirect_url":"https://epoint.az/checkout/abc","card_id":"card-9"}
                """);

        TokenizationSession session = adapter()
                .beginTokenization(new TokenizationRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Money.zero("AZN"),
                        URI.create("https://ideanest.az/checkout/return"),
                        "tokenisation-key-1"));

        assertThat(session.redirectUrl()).hasToString("https://epoint.az/checkout/abc");
        assertThat(session.expiresAt()).isAfter(NOW);
        // R-05 read literally. "refund" is Epoint's word for what the card is for: 0 collects,
        // 1 is a payout destination, and the two registrations are not interchangeable.
        assertThat(whatWasSentTo("/api/1/card-registration").get("refund").asInt()).isZero();
    }

    /**
     * The identifier is this adapter's own, and the shape is why.
     *
     * <p>Epoint's plain registration takes no {@code order_id} and hands the {@code card_id}
     * back at request time, while resolving needs one to ask about and the other to return as
     * the token. Nothing outside the adapter parses it.
     */
    @Test
    @DisplayName("a resolved registration returns the card token and the RRN R-03 is carried on")
    void resolvingAnApprovedRegistration() {
        epointAnswers("/api/1/get-status", """
                {"status":"success","code":"000","rrn":"512345678901","card_mask":"412345******9012"}
                """);

        TokenizationResult result = adapter().resolveTokenization("tokenisation-key-1|card-9");

        assertThat(result.outcome()).isEqualTo(ProviderOutcome.APPROVED);
        assertThat(result.token()).isEqualTo("card-9");
        assertThat(result.schemeTransactionId()).isEqualTo("512345678901");
        assertThat(result.last4()).isEqualTo("9012");
        assertThat(whatWasSentTo("/api/1/get-status").get("order_id").asString()).isEqualTo("tokenisation-key-1");
    }

    /**
     * A card saved without R-03's identifier looks fine on the backer's saved cards and cannot
     * be charged at the campaign's close. {@code TokenizationResult} refuses to be built that
     * way; the adapter must not hand it an empty string instead.
     */
    @Test
    @DisplayName("an approval with no RRN is unreadable rather than a card saved without R-03")
    void anApprovalWithoutAnRrnIsRefused() {
        epointAnswers("/api/1/get-status", """
                {"status":"success","code":"000"}
                """);

        assertThatThrownBy(() -> adapter().resolveTokenization("tokenisation-key-1|card-9"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("R-03");
    }

    // ------------------------------------------------------------------
    // §9.3's R-07 (#434)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a correctly signed delivery is a settled charge")
    void aSignedDeliveryIsVerified() {
        String body = """
                {"order_id":"collection-key-1","status":"success","code":"000","transaction":"te001111111",
                 "operation_code":"100","rrn":"512345678901","amount":120.50}
                """;

        PaymentEvent event = adapter().parseWebhook(formEncoded(body, PRIVATE_KEY), Map.of());

        assertThat(event.provider()).isEqualTo(ProviderName.EPOINT);
        assertThat(event.type()).isEqualTo(PaymentEventType.CHARGE_SUCCEEDED);
        // Epoint's own identifier for the operation, which is what a redelivery repeats.
        // order_id would be wrong: §9.6 makes several attempts against one pledge and each is a
        // different order.
        assertThat(event.providerEventId()).isEqualTo("te001111111:success");
        assertThat(event.providerTransactionId()).isEqualTo("te001111111");
        assertThat(event.amount()).isEqualTo(Money.of(new BigDecimal("120.50"), "AZN"));
        // Epoint signs no timestamp, so §17.2's replay window has nothing to check and
        // ProviderWebhooks skips it. Recorded as a finding in docs/providers/epoint.md rather
        // than invented here: a timestamp this adapter made up would be the platform's clock
        // checked against itself.
        assertThat(event.signedAt()).isNull();
    }

    @Test
    @DisplayName("a body changed after signing is refused")
    void aTamperedBodyIsRefused() {
        byte[] delivery = formEncoded("""
                {"order_id":"o1","status":"success","transaction":"te1","amount":10.00}
                """, PRIVATE_KEY);
        String tampered = new String(delivery, StandardCharsets.UTF_8).replace("data=", "data=x");

        assertThatThrownBy(() -> adapter().parseWebhook(tampered.getBytes(StandardCharsets.UTF_8), Map.of()))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("a delivery signed with somebody else's key is refused")
    void aWrongKeyIsRefused() {
        byte[] delivery = formEncoded("""
                {"order_id":"o1","status":"success","transaction":"te1"}
                """, "not-our-private-key");

        assertThatThrownBy(() -> adapter().parseWebhook(delivery, Map.of()))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("a delivery with no signature at all is refused")
    void anUnsignedDeliveryIsRefused() {
        byte[] delivery = "data=e30%3D".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> adapter().parseWebhook(delivery, Map.of()))
                .isInstanceOf(WebhookVerificationException.class);
    }

    /**
     * Epoint's specification says {@code application/x-www-form-urlencoded}; the production
     * integration this adapter was read against posts {@code multipart/form-data}. Both are one
     * signature over one string, and an adapter that handled only the documented one would fail
     * on the first real delivery.
     */
    @Test
    @DisplayName("a multipart delivery verifies the same way a form-encoded one does")
    void aMultipartDeliveryIsVerified() {
        String json = "{\"order_id\":\"o1\",\"status\":\"success\",\"transaction\":\"te-multipart\"}";
        String data = base64(json);
        String signature = sign(PRIVATE_KEY, data);
        String body = "--X\r\nContent-Disposition: form-data; name=\"data\"\r\n\r\n" + data
                + "\r\n--X\r\nContent-Disposition: form-data; name=\"signature\"\r\n\r\n" + signature
                + "\r\n--X--\r\n";

        PaymentEvent event = adapter().parseWebhook(body.getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(event.providerEventId()).isEqualTo("te-multipart:success");
    }

    /**
     * §9.8's first step has nothing to trigger it, and this test is where that is recorded.
     *
     * <p>Epoint's five statuses contain no dispute, so #68's {@code Dispute} machinery stays
     * unreachable and R-13 is outstanding in {@code docs/providers/epoint.md}. What the adapter
     * must not do is guess: an unrecognised delivery is stored and ignored, because Epoint
     * adding a status must not start failing deliveries and answering 500 makes Epoint retry
     * something nobody wants.
     */
    @Test
    @DisplayName("an event this adapter does not know is unrecognised rather than a failure")
    void anUnknownEventIsIgnoredRatherThanRefused() {
        PaymentEvent event = adapter()
                .parseWebhook(formEncoded("""
                        {"order_id":"o1","status":"chargeback_maybe","transaction":"te2"}
                        """, PRIVATE_KEY), Map.of());

        assertThat(event.type()).isEqualTo(PaymentEventType.UNRECOGNISED);
    }

    /**
     * {@code operation_code} 001 is a card registration and 100 is a customer payment. Routing
     * the first to {@code CHARGE_SUCCEEDED} would settle a pledge on the strength of a card
     * being saved, which is a campaign told it collected money nobody was charged.
     */
    @Test
    @DisplayName("a successful card registration is not a successful charge")
    void aCardRegistrationIsNotACollection() {
        PaymentEvent event = adapter()
                .parseWebhook(formEncoded("""
                        {"order_id":"o1","status":"success","operation_code":"001","transaction":"te3"}
                        """, PRIVATE_KEY), Map.of());

        assertThat(event.type()).isEqualTo(PaymentEventType.UNRECOGNISED);
    }

    /**
     * The same argument, one code along. 200 is "ilk ödənişlə kartın qeydiyyatı" — a
     * registration that also charged, which is §9.2's phase one with a minimal-value
     * verification and not a collection. It arrives looking exactly like a settled charge.
     */
    @Test
    @DisplayName("a registration that charged a verification is not a collection either")
    void aRegistrationWithPayIsNotACollection() {
        PaymentEvent event = adapter()
                .parseWebhook(formEncoded("""
                        {"order_id":"o1","status":"success","operation_code":"200","transaction":"te3b"}
                        """, PRIVATE_KEY), Map.of());

        assertThat(event.type()).isEqualTo(PaymentEventType.UNRECOGNISED);
    }

    /**
     * The identity is the operation <em>and</em> its outcome, and this is the failure that
     * forces it.
     *
     * <p>A charge that settles and is later returned produces two deliveries about one Epoint
     * transaction. An identity that named only the transaction would make the second look like
     * a redelivery of the first, V43's unique index would swallow it, and the platform would
     * have been told the money went back and recorded nothing.
     */
    @Test
    @DisplayName("a return of a settled charge is a second event, not a redelivery of the first")
    void aReturnIsNotADuplicateOfItsCharge() {
        EpointPaymentProvider adapter = adapter();

        PaymentEvent settled = adapter.parseWebhook(formEncoded("""
                {"order_id":"o1","status":"success","operation_code":"100","transaction":"te-same"}
                """, PRIVATE_KEY), Map.of());
        PaymentEvent returned = adapter.parseWebhook(formEncoded("""
                {"order_id":"o1","status":"returned","transaction":"te-same"}
                """, PRIVATE_KEY), Map.of());

        assertThat(settled.providerEventId()).isNotEqualTo(returned.providerEventId());
        assertThat(settled.type()).isEqualTo(PaymentEventType.CHARGE_SUCCEEDED);
        assertThat(returned.type()).isEqualTo(PaymentEventType.REFUND_SUCCEEDED);
    }

    @Test
    @DisplayName("a returned payment is a settled refund")
    void aReturnedPaymentIsARefund() {
        PaymentEvent event = adapter()
                .parseWebhook(formEncoded("""
                        {"order_id":"o1","status":"returned","transaction":"te4","amount":25.00}
                        """, PRIVATE_KEY), Map.of());

        assertThat(event.type()).isEqualTo(PaymentEventType.REFUND_SUCCEEDED);
        assertThat(event.amount()).isEqualTo(Money.of(new BigDecimal("25.00"), "AZN"));
    }

    @Test
    @DisplayName("a failed payment is a failed charge")
    void aFailedPaymentIsAFailedCharge() {
        PaymentEvent event = adapter()
                .parseWebhook(formEncoded("""
                        {"order_id":"o1","status":"failed","code":"116","transaction":"te5"}
                        """, PRIVATE_KEY), Map.of());

        assertThat(event.type()).isEqualTo(PaymentEventType.CHARGE_FAILED);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static EpointPaymentProvider adapter() {
        return adapterWith(confirmed());
    }

    private static EpointPaymentProvider adapterWith(PaymentProperties.Epoint.Capabilities capabilities) {
        PaymentProperties.Epoint epoint = new PaymentProperties.Epoint(
                server.baseUrl(),
                PUBLIC_KEY,
                PRIVATE_KEY,
                "https://ideanest.az/v1/webhooks/psp/epoint",
                "az",
                Duration.ofSeconds(5),
                capabilities);
        return new EpointPaymentProvider(RestClient.builder(), propertiesOf(epoint), JSON, CLOCK);
    }

    /** What a deployment holding #422's outstanding answers in writing would configure. */
    private static PaymentProperties.Epoint.Capabilities confirmed() {
        return new PaymentProperties.Epoint.Capabilities(
                true, true, true, true, false, true, null, List.of("AZN"), List.of("APPLE_PAY", "GOOGLE_PAY"));
    }

    private static PaymentProperties propertiesOf(PaymentProperties.Epoint epoint) {
        return new PaymentProperties(
                new PaymentProperties.Provider("epoint"), null, null, null, null, epoint);
    }

    private static StoredCardChargeRequest charge(Money amount) {
        return new StoredCardChargeRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new StoredCard(UUID.randomUUID(), ProviderName.EPOINT, "card-token-1", "512345678901"),
                amount,
                "IdeaNest",
                1,
                "collection-key-1");
    }

    private static void epointAnswers(String path, String body) {
        server.stubFor(WireMock.post(WireMock.urlEqualTo(path)).willReturn(WireMock.okJson(body)));
    }

    /**
     * The JSON the adapter actually signed and sent, recovered from the request Epoint received.
     *
     * <p>Decoded rather than asserted as a string: what matters is the document, and asserting
     * base64 would be asserting a key order.
     */
    private static JsonNode whatWasSentTo(String path) {
        return JSON.readTree(jsonSentTo(path));
    }

    /** The same document, still as the bytes the signature was taken over. */
    private static String jsonSentTo(String path) {
        List<LoggedRequest> requests = server.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo(path)));
        assertThat(requests).hasSize(1);

        Map<String, String> fields = formFieldsOf(requests.getFirst().getBodyAsString());
        String data = fields.get("data");
        // The signature the adapter sent verifies against the private key it was configured
        // with. Asserted on every request rather than in one test: an adapter that signed with
        // the wrong key would be refused by Epoint and by nothing here.
        assertThat(fields.get("signature")).isEqualTo(sign(PRIVATE_KEY, data));
        return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
    }

    private static Map<String, String> formFieldsOf(String body) {
        return java.util.Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(pair -> pair.length == 2)
                .collect(java.util.stream.Collectors.toMap(
                        pair -> java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    /** A delivery as Epoint posts one: two form fields, the second signing the first. */
    private static byte[] formEncoded(String json, String privateKey) {
        String data = base64(json);
        String body = "data=" + urlEncode(data) + "&signature=" + urlEncode(sign(privateKey, data));
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Epoint's signature, computed here independently of the adapter's own helper.
     *
     * <p>Deliberately a second implementation. A test that called {@code EpointSignature} would
     * assert that the class agrees with itself, which is exactly the assertion that passes while
     * both sides base64 a hex digest instead of the twenty raw bytes.
     */
    private static String sign(String privateKey, String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((privateKey + data + privateKey).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
