package az.ideanest.payout.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.fee.application.FeeBreakdown;
import az.ideanest.fee.application.FeeSchedules;
import az.ideanest.payment.application.CampaignFunds;
import az.ideanest.payment.application.PayoutGateway;
import az.ideanest.payout.PayoutProperties;
import az.ideanest.payout.domain.Payout;
import az.ideanest.payout.domain.PayoutApproval;
import az.ideanest.payout.domain.PayoutState;
import az.ideanest.payout.infrastructure.PayoutApprovalRepository;
import az.ideanest.payout.infrastructure.PayoutRepository;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a creator is owed, and the two signatures before it leaves — §9, §4.11's AD-05,
 * issues #69 and #306.
 *
 * <h2>Four steps, and each one is somebody's decision</h2>
 *
 * <ol>
 *   <li>{@link #calculate} freezes the figure. The campaign has closed, the collections
 *       are summed, the fees are priced against the schedule in force, and the hold
 *       begins.
 *   <li>{@link #queue} shows it, and moves it to {@code PENDING_APPROVAL} when the hold
 *       runs out.
 *   <li>{@link #approve} takes a signature. Above the configured threshold it takes two,
 *       from two different accounts.
 *   <li>{@link #send} instructs the provider.
 * </ol>
 *
 * <h2>The figures are frozen and never recomputed</h2>
 *
 * <p>V55's header has the argument. A payout is derivable from collections, refunds and a
 * fee schedule, and all three move — so recomputing on read produces a different number
 * from the one two people approved, and the approval becomes an approval of nothing in
 * particular. A change afterwards produces a <em>new</em> payout.
 *
 * <p>The corollary is that a refund issued between calculation and sending is not
 * reflected, which would be a real hole if the hold did not exist. {@link #send} therefore
 * re-reads the campaign's funds and refuses if the net has moved — the payout is cancelled
 * and recalculated rather than sent at a figure that is no longer true.
 *
 * <h2>Dual approval is two rows, and they cannot be the same person</h2>
 *
 * <p>V55 makes {@code (payout_id, approver_id)} the primary key, so "two different people"
 * is a constraint rather than a check somebody has to remember. {@code APPROVE_PAYOUT} is
 * held by {@code ADMINISTRATOR} alone and deliberately not by {@code FINANCE} — a role
 * conferring both issuing and approving would make the second signature a formality
 * whenever the finance team is one person.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private static final int PAGE_SIZE = 50;

    private final PayoutRepository payouts;
    private final PayoutApprovalRepository approvals;
    private final PayoutGateway gateway;
    private final FeeSchedules fees;
    private final ProjectSummaries projects;
    private final PlatformStaff staff;
    private final AuditLog audit;
    private final PayoutProperties properties;
    private final Clock clock;

    public PayoutService(
            PayoutRepository payouts,
            PayoutApprovalRepository approvals,
            PayoutGateway gateway,
            FeeSchedules fees,
            ProjectSummaries projects,
            PlatformStaff staff,
            AuditLog audit,
            PayoutProperties properties,
            Clock clock) {
        this.payouts = payouts;
        this.approvals = approvals;
        this.gateway = gateway;
        this.fees = fees;
        this.projects = projects;
        this.staff = staff;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Works out what a campaign owes its creator, and starts the hold.
     *
     * <p><strong>Refuses if one is already in flight.</strong> V55's partial unique index
     * says the same thing and would refuse it anyway; checking first turns a constraint
     * violation into a sentence. Two payouts in flight for one campaign is how a creator
     * gets paid twice for the same collections.
     *
     * @throws PayoutAlreadyInFlightException when the campaign has one
     * @throws NothingToPayException when the campaign has collected nothing, or has
     *     refunded everything it collected
     */
    @Transactional
    public Payout calculate(UUID staffId, UUID projectId) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);

        payouts.inFlightFor(projectId).ifPresent(existing -> {
            throw new PayoutAlreadyInFlightException(projectId, existing.id());
        });

        ProjectSummary campaign = projects
                .summaryOf(projectId)
                .orElseThrow(() -> new UnknownPayoutCampaignException(projectId));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        CampaignFunds funds = gateway.fundsOf(projectId, properties.currency());
        Money payable = funds.net();

        if (!payable.isPositive()) {
            throw new NothingToPayException(projectId);
        }

        // Priced against the schedule in force now, and the schedule's identifier is kept
        // on the row so the arithmetic can be re-derived years later.
        FeeBreakdown breakdown = fees.priceOf(funds.collected(), now, projectId);

        // The fees come off the gross and the refunds come off what is left. Doing it the
        // other way round would charge the platform's fee on money that went back to a
        // backer, which is the trade §9.7 leaves open and this is the reading that does not
        // take a fee on a refund.
        Money net = breakdown.net().minus(funds.refunded());
        if (!net.isPositive()) {
            throw new NothingToPayException(projectId);
        }

        short required = approvalsRequiredFor(net);

        Payout calculated = payouts.save(Payout.calculated(
                projectId,
                campaign.creatorId(),
                funds.collected(),
                breakdown.platformFee(),
                breakdown.processingFee(),
                funds.refunded(),
                net,
                breakdown.scheduleId(),
                now.plus(properties.hold()),
                required,
                "payout-" + projectId + "-" + now.toEpochMilli()));

        audit.record(
                AuditAction.PAYOUT_CALCULATED,
                calculated.id(),
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "project=%s; gross=%s; fees=%s; refunded=%s; net=%s; approvals=%d"
                        .formatted(
                                projectId,
                                funds.collected(),
                                breakdown.totalFees(),
                                funds.refunded(),
                                net,
                                required));

        log.info("Payout {} calculated for campaign {}: net {}", calculated.id(), projectId, net);
        return calculated;
    }

    /**
     * How many signatures this payout needs.
     *
     * <p><strong>A payout in another currency takes the higher count.</strong> §21.2 gives
     * nothing to convert with, so the configured threshold does not apply — and the safe
     * branch when a rule cannot be evaluated is the stricter one. The alternative, treating
     * an uncomparable amount as below the threshold, would make every foreign-currency
     * payout the one that needs no second opinion.
     */
    private short approvalsRequiredFor(Money net) {
        if (!net.currency().equals(properties.currency())) {
            return properties.approvalsAboveThreshold();
        }
        return net.amount().compareTo(properties.dualApprovalThreshold()) > 0
                ? properties.approvalsAboveThreshold()
                : (short) 1;
    }

    /**
     * The queue: everything still on its way, oldest first.
     *
     * <p>Moves anything whose hold has expired to {@code PENDING_APPROVAL} as it lists it.
     * {@code Payout.payable} has the argument for why that is here rather than in a
     * scheduled job: a job would be a second thing to go wrong before anybody could be
     * paid, and the state is derivable from the row and the clock.
     */
    @Transactional
    public List<Payout> queue(UUID staffId, int page) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);
        payouts.nowPayable(clock.instant()).forEach(Payout::payable);

        return payouts.queue(PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /** Everything, newest first, optionally narrowed to one state. */
    @Transactional(readOnly = true)
    public List<Payout> list(UUID staffId, PayoutState state, int page) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);
        PageRequest request = PageRequest.of(Math.max(page, 0), PAGE_SIZE);

        return state == null ? payouts.page(request) : payouts.pageByState(state, request);
    }

    /** One payout and who has signed it. */
    @Transactional(readOnly = true)
    public PayoutFile inspect(UUID staffId, UUID payoutId) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);
        Payout payout = payouts.findById(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));

        return new PayoutFile(payout, approvals.forPayout(payoutId));
    }

    /**
     * Signs off a payout.
     *
     * <p><strong>Only once the hold has expired.</strong> Approving during the hold would
     * let a payout be signed before the refunds and chargebacks it is meant to wait for
     * have landed, which is the whole purpose of the hold — the signature would be on a
     * figure nobody could yet know was right.
     *
     * <p>Signing twice is not an error and is not a second signature: V55's primary key
     * makes it a no-op, and the response says how many are still needed.
     *
     * @throws PayoutNotApprovableException when the payout is not waiting for signatures
     */
    @Transactional
    public PayoutFile approve(UUID staffId, UUID payoutId, String note) {
        staff.requireCapability(staffId, StaffCapability.APPROVE_PAYOUT);

        Payout payout = payouts.findAndLock(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));
        if (payout.state() == PayoutState.CALCULATED && payout.isPayableAt(clock.instant())) {
            payout.payable();
        }
        if (payout.state() != PayoutState.PENDING_APPROVAL) {
            throw new PayoutNotApprovableException(payoutId, payout.state());
        }

        int written = approvals.approveIfAbsent(payoutId, staffId, note);
        long signatures = approvals.countFor(payoutId);

        if (signatures >= payout.approvalsRequired()) {
            payout.approved();
        }

        audit.record(
                AuditAction.PAYOUT_APPROVED,
                payoutId,
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                "new=%s; signatures=%d/%d".formatted(written == 1, signatures, payout.approvalsRequired()));

        log.info("Payout {} approved by {} ({}/{})", payoutId, staffId, signatures, payout.approvalsRequired());
        return new PayoutFile(payout, approvals.forPayout(payoutId));
    }

    /** Withdraws a signature, before the payout has been sent. */
    @Transactional
    public PayoutFile withdrawApproval(UUID staffId, UUID payoutId) {
        staff.requireCapability(staffId, StaffCapability.APPROVE_PAYOUT);

        Payout payout = payouts.findAndLock(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));
        if (!payout.state().isInFlight()) {
            throw new PayoutNotApprovableException(payoutId, payout.state());
        }

        int removed = approvals.withdraw(payoutId, staffId);
        long signatures = approvals.countFor(payoutId);

        // Back to waiting, if the withdrawal took it below the bar. Without this a payout
        // could be approved, un-approved, and still sent — which is the whole rule defeated
        // by a button.
        if (payout.state() == PayoutState.APPROVED && signatures < payout.approvalsRequired()) {
            payout.payable();
        }

        audit.record(
                AuditAction.PAYOUT_APPROVED,
                payoutId,
                AuditActor.moderator(staffId),
                AuditOutcome.REFUSED,
                "withdrawn=%s; signatures=%d/%d".formatted(removed == 1, signatures, payout.approvalsRequired()));

        return new PayoutFile(payout, approvals.forPayout(payoutId));
    }

    /**
     * Sends the money.
     *
     * <p><strong>The figures are re-checked against the campaign's funds first.</strong>
     * A refund issued between calculation and sending would otherwise be paid out anyway —
     * the figures are frozen, which is right for the approval and wrong for the
     * instruction. When the net has moved, the payout is refused rather than adjusted: a
     * different amount is a different decision, and the signatures on file were given for
     * this one.
     *
     * @throws PayoutNotSendableException when it has not been approved, or the figures have
     *     moved underneath it
     */
    @Transactional
    public Payout send(UUID staffId, UUID payoutId, String destinationReference) {
        staff.requireCapability(staffId, StaffCapability.APPROVE_PAYOUT);

        Payout payout = payouts.findAndLock(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));
        if (payout.state() != PayoutState.APPROVED) {
            throw new PayoutNotSendableException(payoutId, payout.state());
        }

        CampaignFunds funds = gateway.fundsOf(payout.projectId(), payout.currency());
        if (!funds.collected().equals(payout.gross()) || !funds.refunded().equals(payout.refunded())) {
            payout.cancelled();
            audit.record(
                    AuditAction.PAYOUT_SENT,
                    payoutId,
                    AuditActor.moderator(staffId),
                    AuditOutcome.REFUSED,
                    "figuresMoved; gross=%s->%s; refunded=%s->%s"
                            .formatted(payout.gross(), funds.collected(), payout.refunded(), funds.refunded()));

            throw new PayoutNotSendableException(payoutId, payout.state());
        }

        PayoutGateway.Sent sent = gateway.send(
                payoutId,
                payout.projectId(),
                payout.creatorId(),
                payout.net(),
                destinationReference,
                payout.idempotencyKey());

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (sent.moved()) {
            payout.paid(sent.transactionId(), now);
        } else {
            payout.failed(sent.failureCode(), sent.failureMessage(), now);
        }

        audit.record(
                AuditAction.PAYOUT_SENT,
                payoutId,
                AuditActor.moderator(staffId),
                sent.moved() ? AuditOutcome.SUCCEEDED : AuditOutcome.REFUSED,
                "amount=%s; transaction=%s; failure=%s"
                        .formatted(payout.net(), sent.transactionId(), sent.failureCode()));

        log.info("Payout {} send attempted by {}: moved={}", payoutId, staffId, sent.moved());
        return payout;
    }

    /** Withdraws a payout before it is sent. */
    @Transactional
    public Payout cancel(UUID staffId, UUID payoutId) {
        staff.requireCapability(staffId, StaffCapability.APPROVE_PAYOUT);

        Payout payout = payouts.findAndLock(payoutId).orElseThrow(() -> new PayoutNotFoundException(payoutId));
        if (!payout.state().isInFlight()) {
            throw new PayoutNotSendableException(payoutId, payout.state());
        }

        payout.cancelled();

        audit.record(
                AuditAction.PAYOUT_SENT,
                payoutId,
                AuditActor.moderator(staffId),
                AuditOutcome.REFUSED,
                "cancelled before sending");

        return payout;
    }

    /** One payout with its signatures. */
    public record PayoutFile(Payout payout, List<PayoutApproval> approvals) {

        /** How many more signatures it needs. Zero once it is approved. */
        public long stillNeeded() {
            return Math.max(0, payout.approvalsRequired() - approvals.size());
        }
    }

    /** The in-flight payout for a campaign, if it has one. Used by the console's detail. */
    @Transactional(readOnly = true)
    public Optional<Payout> inFlightFor(UUID staffId, UUID projectId) {
        staff.requireCapability(staffId, StaffCapability.VIEW_FINANCE);
        return payouts.inFlightFor(projectId);
    }
}
