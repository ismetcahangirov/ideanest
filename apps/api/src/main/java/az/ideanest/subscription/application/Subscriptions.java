package az.ideanest.subscription.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import az.ideanest.subscription.domain.Subscription;
import az.ideanest.subscription.domain.SubscriptionPlan;
import az.ideanest.subscription.domain.SubscriptionState;
import az.ideanest.subscription.infrastructure.SubscriptionPlanRepository;
import az.ideanest.subscription.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying, holding and ending a subscription.
 *
 * <h2>Two steps for a paid plan, and why that is not a stub</h2>
 *
 * <p>Nothing on this platform can charge a card. §9.2 ships no payment provider adapter
 * while #60 — confirm §9.3's fourteen capabilities in writing — is unanswered, and
 * {@code PaymentProvider}'s header says plainly why no stub adapter ships in the meantime:
 * an adapter that returned an approval would make the path look finished.
 *
 * <p>So a priced plan is bought into {@link SubscriptionState#PENDING_PAYMENT}, and a
 * member of staff records that the transfer arrived. That is how a platform with no
 * processor actually sells — an invoice and a bank transfer — and it is audited under the
 * name of whoever confirmed it. When #60 lands, the provider's callback replaces
 * {@link #activate} and nothing above it changes: the states, the gate and the pricing
 * page are already the shape a provider needs.
 *
 * <p>A plan priced at zero has no second step. {@link #subscribe} activates it on the
 * spot, because there is no payment to wait for.
 *
 * <h2>One open subscription per account, enforced by an index rather than a read</h2>
 *
 * <p>V62's partial unique index is what makes it true, and {@link #subscribe} turns the
 * violation into a refusal. A read-then-write would let two purchases arriving together
 * both find nothing and both insert.
 *
 * <p>The same index cannot consult a clock, so an {@code ACTIVE} row whose period ended
 * last week would block the account from buying again. {@link #subscribe} retires it
 * inside its own transaction, immediately before inserting — which is why there is no
 * sweep job doing it on a schedule. V62's header has that argument.
 */
@Service
public class Subscriptions {

    private static final Logger log = LoggerFactory.getLogger(Subscriptions.class);

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final PlatformStaff staff;
    private final AuditLog audit;
    private final Clock clock;

    public Subscriptions(
            SubscriptionRepository subscriptions,
            SubscriptionPlanRepository plans,
            PlatformStaff staff,
            AuditLog audit,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.staff = staff;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What this account holds, if anything that has not ended.
     *
     * <p>Returns a lapsed subscription too, and that is deliberate: "your Growth plan
     * ended on 3 August" is a different sentence from "you have never subscribed", and a
     * query that filtered on the clock could not tell a creator which one they are in.
     * {@link Subscription#entitlesAt} is what decides whether it still buys anything.
     */
    @Transactional(readOnly = true)
    public Optional<Subscription> heldBy(UUID accountId) {
        return subscriptions.openFor(accountId);
    }

    /** Everything this account has ever held, newest first. */
    @Transactional(readOnly = true)
    public List<Subscription> historyOf(UUID accountId) {
        return subscriptions.historyFor(accountId);
    }

    /**
     * Buys a plan.
     *
     * <p>Three things happen in one transaction: a lapsed row is retired, the purchase is
     * written, and — for a free plan — the entitlement opens. They are one transaction
     * because the first is only safe as part of the second: retiring somebody's expired
     * subscription and then failing to write its replacement would leave an account with
     * nothing, having asked for something.
     *
     * @param planId which plan. Must be on sale; a subscription against an unlisted plan
     *     would be a purchase from a catalogue that no longer offers it
     * @throws AlreadySubscribedException when the account holds one that has not ended.
     *     Changing plan is cancel-then-subscribe, because upgrading mid-period needs a
     *     provider that can refund a part-month
     */
    @Transactional
    public Subscription subscribe(UUID accountId, UUID planId) {
        SubscriptionPlan plan = plans.findById(planId).orElseThrow(() -> new UnknownPlanException(planId));
        if (!plan.isListed()) {
            throw new PlanNotOnSaleException(planId);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Optional<Subscription> open = subscriptions.openFor(accountId);
        if (open.isPresent()) {
            Subscription existing = open.get();
            if (!existing.hasLapsedBy(now)) {
                throw new AlreadySubscribedException(existing.getState() == SubscriptionState.PENDING_PAYMENT);
            }
            // Its period ran out and the index does not know. Retired here rather than by
            // a job, by the person it was in the way of, at the moment it was in the way.
            existing.expire(now);
            subscriptions.saveAndFlush(existing);
        }

        Subscription bought = plan.requiresPayment()
                ? Subscription.awaitingPayment(Identifiers.newIdentifier(), accountId, plan, now)
                : Subscription.activeFrom(Identifiers.newIdentifier(), accountId, plan, now);

        Subscription saved;
        try {
            saved = subscriptions.saveAndFlush(bought);
        } catch (DataIntegrityViolationException e) {
            // Two purchases arrived together. The index caught it; the loser is told they
            // already have one rather than shown a 500.
            throw new AlreadySubscribedException(false);
        }

        log.info(
                "Account {} subscribed to plan {} ({}), state {}",
                accountId,
                plan.getId(),
                plan.getCode(),
                saved.getState());
        return saved;
    }

    /**
     * The creator's own cancellation: keep the period, do not renew.
     *
     * <p>The entitlement runs to {@code current_period_end}. Taking it away the moment
     * they click would be charging for a month and then withdrawing it, and a creator with
     * a live campaign would lose the ability to submit the next one for a period they have
     * already paid for.
     *
     * <p>A subscription still waiting for payment is cancelled outright instead — there is
     * no period to run out, and leaving it open would keep a row in the console's queue
     * for a purchase somebody has abandoned.
     */
    @Transactional
    public Subscription cancel(UUID accountId) {
        Subscription subscription = subscriptions.openFor(accountId).orElseThrow(NoSubscriptionException::new);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (subscription.getState() == SubscriptionState.PENDING_PAYMENT) {
            subscription.cancelNow(null, now);
        } else {
            subscription.cancelAtPeriodEnd(now);
        }

        log.info("Account {} cancelled subscription {}", accountId, subscription.getId());
        return subscription;
    }

    /* ---------------------------------------------------------------------
     * The console
     * ------------------------------------------------------------------ */

    /**
     * Everything, or just what is waiting for a payment to be recorded.
     *
     * @param awaitingPaymentOnly the queue rather than the archive. The queue is what the
     *     screen opens on, because it is the only part of this that is somebody's work
     */
    @Transactional(readOnly = true)
    public List<Subscription> forConsole(UUID staffId, boolean awaitingPaymentOnly) {
        staff.requireCapability(staffId, StaffCapability.CONFIGURE_PLATFORM);
        return awaitingPaymentOnly
                ? subscriptions.inState(SubscriptionState.PENDING_PAYMENT)
                : subscriptions.recent();
    }

    /**
     * Records that the payment arrived, and opens the period.
     *
     * <p>The period starts now rather than when the plan was chosen, so a creator who
     * waited three days for a transfer to clear gets the month they paid for.
     *
     * @param note the transfer reference or invoice number. Not required by the column and
     *     required here in spirit only — the audit row carries who and when regardless,
     *     and refusing an activation for a missing reference would leave a paying creator
     *     waiting while somebody looks one up
     * @throws SubscriptionNotAwaitingPaymentException when a colleague got there first
     */
    @Transactional
    public Subscription activate(UUID staffId, UUID subscriptionId, String note) {
        staff.requireCapability(staffId, StaffCapability.CONFIGURE_PLATFORM);

        Subscription subscription = subscriptions
                .findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));

        if (subscription.getState() != SubscriptionState.PENDING_PAYMENT) {
            throw new SubscriptionNotAwaitingPaymentException(subscriptionId, subscription.getState());
        }

        SubscriptionPlan plan = plans.findById(subscription.getPlanId())
                .orElseThrow(() -> new UnknownPlanException(subscription.getPlanId()));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        subscription.activate(plan, staffId, note, now);

        audit.record(
                AuditAction.SUBSCRIPTION_ACTIVATED,
                subscription.getId(),
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "account=%s; plan=%s; paid=%s; until=%s"
                        .formatted(
                                subscription.getAccountId(),
                                plan.getCode(),
                                subscription.getPrice(),
                                subscription.getCurrentPeriodEnd()));

        log.info("Subscription {} activated by {}", subscription.getId(), staffId);
        return subscription;
    }

    /**
     * Staff ending a subscription outright — a chargeback, a fraud finding, or a purchase
     * somebody made by mistake.
     *
     * <p>Immediate, unlike the creator's own cancellation, because the reasons are
     * different: a creator who cancels has paid for the period, and an account whose
     * payment was reversed has not.
     *
     * @param reason recorded on the row and on the audit entry. Required: this takes
     *     something away from somebody, and the creator asking why is entitled to an answer
     *     that exists
     */
    @Transactional
    public Subscription end(UUID staffId, UUID subscriptionId, String reason) {
        staff.requireCapability(staffId, StaffCapability.CONFIGURE_PLATFORM);

        Subscription subscription = subscriptions
                .findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        subscription.cancelNow(reason, now);

        audit.record(
                AuditAction.SUBSCRIPTION_CANCELED,
                subscription.getId(),
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "account=%s; reason=%s".formatted(subscription.getAccountId(), reason));

        log.info("Subscription {} ended by {}", subscription.getId(), staffId);
        return subscription;
    }
}
