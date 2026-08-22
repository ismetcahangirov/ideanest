package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the payment module is allowed to do to a pledge while collecting it (#64, #65).
 *
 * <p><strong>This class is the whole of the pledge module's side of epic #59.</strong>
 * §16.1 says a module reaches another through its application layer, so the collection
 * run holds {@link ChargeablePledge} records rather than entities and asks for state
 * changes by identifier. The value of that is not tidiness: it means every edge of
 * §6.2's collection sub-machine is performed in one file, by methods that each state
 * their own precondition, rather than by whichever service happened to be holding the
 * row.
 *
 * <h2>{@code MANDATORY}, everywhere, deliberately</h2>
 *
 * <p>Every method here refuses to run outside a transaction the caller started, and
 * the reason is the same one {@code Ledger} gives with more at stake. A collection
 * attempt is one commit containing four things: the claim on the pledge, the
 * {@code transactions} row, the ledger posting, and the outbox event that tells the
 * backer. Any of them alone is a distinct kind of wrong — a pledge that says
 * {@code COLLECTED} with no ledger entry is money nobody can account for, and a ledger
 * entry with no pledge is a charge nobody can attribute. Starting a transaction here
 * would let each of them commit on its own.
 *
 * <p>The one exception is {@link #pastTheirWindow}, which is a read the sweep does
 * before it opens any transaction at all.
 */
@Service
public class PledgeCollection {

    private final PledgeRepository pledges;

    public PledgeCollection(PledgeRepository pledges) {
        this.pledges = pledges;
    }

    /**
     * §6.2's {@code CONFIRMED → CHARGE_PENDING} for every confirmed pledge on a campaign.
     *
     * <p>One statement, for a campaign that may have thousands of them. See
     * {@code PledgeRepository#queueConfirmedPledges}.
     *
     * @param firstAttemptAt §9.6's first row, "immediately after close": the pass's own
     *     instant
     * @param windowEndsAt §9.6's seven days, frozen onto each pledge. V42 has the
     *     argument for why it is stored rather than recomputed
     * @return how many pledges are now queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int queueForCollection(UUID projectId, Instant firstAttemptAt, Instant windowEndsAt) {
        return pledges.queueConfirmedPledges(projectId, firstAttemptAt, windowEndsAt);
    }

    /**
     * Takes the next pledge due an attempt in this queue, locking it for the caller's
     * transaction.
     *
     * <p>The claim <em>is</em> the lock — see {@code PledgeRepository#claimNextDueForCharge}
     * — so a caller holding one of these may spend the next few seconds talking to a
     * provider about that pledge without any other replica doing the same.
     *
     * @return the pledge, or empty when this queue has nothing due
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChargeablePledge> claimNextDue(CollectionStage stage, Instant now) {
        return pledges.claimNextDueForCharge(stage.state().name(), now).map(PledgeCollection::describe);
    }

    /**
     * §6.2's {@code CHARGE_PENDING → COLLECTED}: the card was charged.
     *
     * <p>Loaded by identifier rather than taken as an entity, because the caller is
     * another module and holds only a {@link ChargeablePledge}. The row is already locked
     * by the claim in the same transaction, so this is a first-level cache hit rather
     * than a second round trip.
     *
     * @throws PledgeNotFoundException when the pledge has gone, which cannot happen
     *     inside a transaction that has it locked and is therefore a bug rather than a
     *     race
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCollected(UUID pledgeId, Instant at) {
        load(pledgeId).collected(at);
    }

    /**
     * §6.2's {@code CHARGE_PENDING → CHARGE_FAILED}, or the loop back into it: the card
     * was refused and §9.6 says when to try again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailure(UUID pledgeId, Instant nextAttemptAt) {
        load(pledgeId).chargeFailed(nextAttemptAt);
    }

    /**
     * The provider took the instruction and has not decided: the same attempt, later.
     *
     * <p>Neither the state nor the attempt count moves. See {@code Pledge#chargeUnresolved}
     * for why counting it would be wrong and for why leaving the number alone is also
     * what keeps the idempotency key stable.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUnresolved(UUID pledgeId, Instant recheckAt) {
        load(pledgeId).chargeUnresolved(recheckAt);
    }

    /** §6.2's {@code CHARGE_FAILED → DROPPED}: §9.6's window elapsed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDropped(UUID pledgeId, Instant at) {
        load(pledgeId).dropped(at);
    }

    /**
     * Pledges whose §9.6 window has run out, oldest first and bounded.
     *
     * <p>Read-only and outside the sweep's per-pledge transactions, for
     * {@code ReservationCleanerJob}'s reason: a batch read in one transaction and acted
     * on in another is a set of decisions taken against a state that has since moved, so
     * the identifiers are read here and each row is re-judged under its own lock.
     */
    @Transactional(readOnly = true)
    public List<UUID> pastTheirWindow(Instant now, int limit) {
        return pledges.findPastTheirChargeWindow(now, PageRequest.ofSize(limit));
    }

    /**
     * One pledge, locked, re-read under that lock.
     *
     * <p>What the drop sweep uses: the identifier came from an unlocked read, so the row
     * has to be re-judged before it is ended — a pledge that was collected in the second
     * between the two must not then be dropped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChargeablePledge> claimForDropping(UUID pledgeId, Instant now) {
        return pledges.findByIdForUpdate(pledgeId)
                .filter(pledge -> pledge.isPastItsChargeWindow(now))
                .map(PledgeCollection::describe);
    }

    /** How many pledges on this campaign are still owed an attempt. For the collection's progress. */
    @Transactional(readOnly = true)
    public long outstanding(UUID projectId) {
        return pledges.countByProjectIdAndStateIn(
                projectId,
                List.of(
                        PledgeState.CHARGE_PENDING,
                        PledgeState.CHARGE_FAILED));
    }

    private Pledge load(UUID pledgeId) {
        return pledges.findById(pledgeId).orElseThrow(() -> new PledgeNotFoundException(pledgeId));
    }

    /**
     * The entity, as much of it as another module may see.
     *
     * <p>{@code attemptNumber} is the pledge's count <em>plus one</em>: it is the number
     * of the attempt about to be made, not of the last one that was. Deriving it here
     * rather than at the call site is what makes the number on the transaction row, the
     * number inside the idempotency key, and the number §9.6's notification reports the
     * same number.
     */
    private static ChargeablePledge describe(Pledge pledge) {
        return new ChargeablePledge(
                pledge.getId(),
                pledge.getProjectId(),
                pledge.getBackerId(),
                Money.of(pledge.getTotalAmount(), pledge.getCurrency()),
                pledge.getPaymentMethodId(),
                pledge.getChargeAttempts() + 1,
                pledge.getChargeWindowEndsAt());
    }
}
