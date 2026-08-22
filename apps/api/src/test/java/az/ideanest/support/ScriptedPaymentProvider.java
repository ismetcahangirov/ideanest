package az.ideanest.support;

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
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A payment provider a test writes the answers for.
 *
 * <p><strong>This is the stub §9.2 refuses, and it is allowed here for exactly the
 * reason it is refused there.</strong> §9.2's objection is that an adapter returning an
 * approval "would make this path look finished and would have told clients that cards
 * were verified when no card was ever seen" — an objection about a deployed environment
 * and about what clients are told. In the suite nobody is told anything and nothing is
 * deployed; what a scripted provider buys is the only way to exercise §9.6's decline
 * schedule, the circuit breaker, and the ledger posting before #60 is answered.
 *
 * <p>It lives in {@code src/test} for that reason and no other, and
 * {@code PaymentProviderBoundaryTests} asserts that {@code src/main} contains no
 * implementation of {@link PaymentProvider} at all.
 *
 * <h2>How a test uses it</h2>
 *
 * <pre>{@code
 * provider.willApprove();               // every charge from now on
 * provider.willDecline("insufficient_funds");
 * provider.willBeUnavailable();
 * provider.nextCharge(approval());      // one answer, then back to the default
 * }</pre>
 *
 * <p>Answers queued with {@link #nextCharge} are taken first, one per call; the default
 * set by the {@code will…} methods answers everything after them. That split is what
 * lets a test say "the first two attempts fail and the third succeeds" without writing a
 * counter.
 */
public class ScriptedPaymentProvider implements PaymentProvider {

    /**
     * Payriff, because §9.3 lists it first and the test profile names it. The value is
     * arbitrary and the fact that it is fixed is not: it is stored on every
     * {@code transactions} row a test writes, and a provider that varied would make those
     * rows unassertable.
     */
    private static final ProviderName NAME = ProviderName.PAYRIFF;

    private final Deque<Object> scripted = new ArrayDeque<>();
    private final List<StoredCardChargeRequest> charges = new ArrayList<>();
    private final AtomicInteger providerTransactionCounter = new AtomicInteger();

    /** The standing answer, once the queue is empty. Approving, so a test that says nothing gets the happy path. */
    private volatile Object standing = ProviderOutcome.APPROVED;

    private volatile String declineCode = "card_declined";

    // ------------------------------------------------------------------
    // Scripting
    // ------------------------------------------------------------------

    /** Every charge from now on is approved. */
    public void willApprove() {
        standing = ProviderOutcome.APPROVED;
    }

    /** Every charge from now on is refused with this code. */
    public void willDecline(String code) {
        declineCode = code;
        standing = ProviderOutcome.DECLINED;
    }

    /** Every charge from now on is accepted and left undecided. */
    public void willLeavePending() {
        standing = ProviderOutcome.PENDING;
    }

    /**
     * Every charge from now on throws.
     *
     * <p>The case the circuit breaker exists for, and the one a test most needs to be
     * able to produce: a provider that cannot be reached is not a decline, and the
     * difference decides whether a backer loses one of §9.6's four attempts.
     */
    public void willBeUnavailable() {
        standing = Unavailable.INSTANCE;
    }

    /** One answer, used by the next charge only. Queued behind any others already added. */
    public void nextCharge(ProviderOutcome outcome) {
        scripted.addLast(outcome);
    }

    /** One unreachable answer, used by the next charge only. */
    public void nextChargeUnavailable() {
        scripted.addLast(Unavailable.INSTANCE);
    }

    /**
     * Forgets the script and every recorded call. Called between tests.
     *
     * <p><strong>The provider transaction counter is deliberately not reset.</strong>
     * {@code transactions_settled_provider_key} is unique over
     * {@code (provider, provider_transaction_id)} across the whole table, and V41 makes
     * that table append-only — so rows from an earlier test in the shared container are
     * still there. A counter that restarted would collide with them, and the failure
     * would look like a bug in the code under test rather than in the fixture.
     */
    public void reset() {
        scripted.clear();
        charges.clear();
        standing = ProviderOutcome.APPROVED;
        declineCode = "card_declined";
    }

    // ------------------------------------------------------------------
    // What was asked
    // ------------------------------------------------------------------

    /** Every charge this provider was asked to make, in order. */
    public List<StoredCardChargeRequest> charges() {
        return List.copyOf(charges);
    }

    /** How many charges were attempted. The assertion most tests actually want. */
    public int chargeCount() {
        return charges.size();
    }

    /** The idempotency keys, in order. What proves a retry of one attempt is not a second charge. */
    public List<String> idempotencyKeys() {
        return charges.stream().map(StoredCardChargeRequest::idempotencyKey).toList();
    }

    // ------------------------------------------------------------------
    // PaymentProvider
    // ------------------------------------------------------------------

    @Override
    public ProviderName name() {
        return NAME;
    }

    @Override
    public ChargeResult chargeStoredCard(StoredCardChargeRequest request) {
        charges.add(request);
        Object answer = scripted.isEmpty() ? standing : scripted.pollFirst();
        if (answer == Unavailable.INSTANCE) {
            throw new ProviderUnavailableException(NAME, "The scripted provider is unavailable");
        }
        ProviderOutcome outcome = (ProviderOutcome) answer;
        return switch (outcome) {
            case APPROVED -> new ChargeResult(outcome, nextProviderTransactionId(), null, null, "{\"scripted\":true}");
            case DECLINED -> new ChargeResult(
                    outcome, nextProviderTransactionId(), declineCode, "Scripted decline", "{\"scripted\":true}");
            case PENDING -> new ChargeResult(outcome, nextProviderTransactionId(), null, null, "{\"scripted\":true}");
        };
    }

    /**
     * §9.3's fourteen, answered as a provider that meets the three
     * {@code PaymentProviders} insists on.
     *
     * <p>{@code preAuthHoldDays} is 7, which is §9.1's whole problem: a hold that expires
     * before a thirty-day campaign closes is why the platform stores a card instead.
     */
    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                true, true, 7, true, false, true, Set.of(), Set.of("AZN", "USD", "EUR"));
    }

    // ------------------------------------------------------------------
    // Not exercised: #55, #67 and #69 own these calls
    // ------------------------------------------------------------------

    @Override
    public TokenizationSession beginTokenization(TokenizationRequest request) {
        throw new UnsupportedOperationException("Tokenisation is #55; no test drives it yet");
    }

    @Override
    public TokenizationResult resolveTokenization(String sessionId) {
        throw new UnsupportedOperationException("Tokenisation is #55; no test drives it yet");
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        throw new UnsupportedOperationException("Refunds are #67; no test drives them yet");
    }

    @Override
    public PayoutResult payout(PayoutRequest request) {
        throw new UnsupportedOperationException("Payouts are #69; no test drives them yet");
    }

    /**
     * A signature scheme simple enough to be obviously right and real enough to refuse a
     * forgery: the body must be JSON carrying an {@code id} and a {@code type}, and the
     * {@code x-scripted-signature} header must equal the shared secret.
     *
     * <p>A real adapter computes an HMAC over the bytes. This does not, because what the
     * suite is testing is {@code ProviderWebhooks} — that an unverifiable body never
     * becomes an event, that a stale timestamp is refused, and that a redelivery does
     * nothing twice — and none of those depend on which MAC the provider chose. What they
     * do depend on is that verification and parsing are one call, which this honours.
     */
    @Override
    public PaymentEvent parseWebhook(byte[] rawBody, Map<String, String> headers) {
        return ScriptedWebhooks.parse(NAME, rawBody, headers);
    }

    private String nextProviderTransactionId() {
        return "scripted-" + providerTransactionCounter.incrementAndGet();
    }

    /** A sentinel for "throw", so that the script can hold outcomes and failures in one queue. */
    private enum Unavailable {
        INSTANCE
    }

    /** Convenience for a test that wants to assert on an amount without rebuilding one. */
    public static Money azn(String amount) {
        return Money.of(new java.math.BigDecimal(amount), "AZN");
    }

    /** The event types the scripted webhook parser understands. Everything else is UNRECOGNISED. */
    public static Set<PaymentEventType> knownWebhookTypes() {
        return Set.of(
                PaymentEventType.CHARGE_SUCCEEDED,
                PaymentEventType.CHARGE_FAILED,
                PaymentEventType.REFUND_SUCCEEDED,
                PaymentEventType.CHARGEBACK_OPENED);
    }

    /** The instant a scripted delivery claims to have been signed at, when a test does not say. */
    public static Instant defaultSignedAt() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }
}
