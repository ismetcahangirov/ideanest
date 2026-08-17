package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.infrastructure.PledgeAddonRepository;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import az.ideanest.project.application.PledgeAcceptance;
import az.ideanest.reward.application.RewardStock;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a backer does with a pledge: make one, read it, confirm it, change it, and
 * withdraw it.
 *
 * <p>§4.5's flow, minus the two halves that belong elsewhere. Reserving the place,
 * pricing the selection and moving a place from one tier to another are
 * {@link ReservationService}, which #51 built and #52 and #56 extended; replaying a
 * retried request is {@code shared.idempotency}, which wraps every mutation here.
 * What is left is the ordering, the campaign's own answer about whether it will take
 * a pledge, and §6.2's transitions.
 *
 * <p><strong>{@link #requireEditable} is the one place that says whether a backer
 * may still act on a pledge.</strong> §4.5's PL-09 and PL-10 are each bounded by two
 * facts owned by two modules — §6.2's states here, the campaign's deadline in
 * {@code project} — and scattering that pair across the edit and the cancellation
 * would be two rules free to drift apart.
 *
 * <p><strong>Where the campaign check lives, and why here.</strong>
 * {@code ReservationService} deliberately does not ask whether the campaign is
 * taking pledges — its javadoc says so — because that is a question about
 * {@code projects} and answering it there would make the pledge module depend on the
 * project module in order to refuse the same request twice. It is asked here,
 * through {@link PledgeAcceptance}, which is the project module's application layer
 * and the only part of it this module may see. {@code ModuleBoundaryTests} fails the
 * build over the alternative.
 *
 * <p><strong>Nothing here charges anything.</strong> §9.2 is explicit: at
 * confirmation the card is verified and the verification is voided, no money moves,
 * and no ledger entry is written. Today not even the verification happens — see
 * {@link PledgeCapability#CARD_VERIFICATION}, which names the issue that owns it.
 */
@Service
public class PledgeService {

    private static final Logger log = LoggerFactory.getLogger(PledgeService.class);

    private final ReservationService reservations;
    private final PledgeAcceptance acceptance;
    private final PledgeRepository pledges;
    private final PledgeAddonRepository addons;
    private final RewardStock stock;
    private final Clock clock;

    public PledgeService(
            ReservationService reservations,
            PledgeAcceptance acceptance,
            PledgeRepository pledges,
            PledgeAddonRepository addons,
            RewardStock stock,
            Clock clock) {
        this.reservations = reservations;
        this.acceptance = acceptance;
        this.pledges = pledges;
        this.addons = addons;
        this.stock = stock;
        this.clock = clock;
    }

    /**
     * {@code POST /v1/pledges/draft}: reserve the place, price the selection, write
     * the draft.
     *
     * <p><strong>The campaign is asked first</strong>, before a place is taken or a
     * price is read. A campaign that is not taking pledges refuses every selection,
     * so checking it after would mean reserving stock on a campaign that is over and
     * relying on the rollback — correct, and a lock taken for nothing on the one row
     * every checkout for that campaign contends on.
     *
     * @throws az.ideanest.project.application.ProjectNotFoundException when there is
     *     no such campaign
     * @throws az.ideanest.project.application.ProjectNotAcceptingPledgesException
     *     when it is not live — §10.4's {@code PROJECT_NOT_LIVE}
     */
    @Transactional
    public PledgeDetail draft(DraftPledge command) {
        acceptance.requireAcceptingPledges(command.projectId());
        return detailOf(reservations.draft(command));
    }

    /**
     * {@code GET /v1/pledges/{id}}: the backer's own pledge.
     *
     * @throws PledgeNotFoundException for an identifier that does not exist and for
     *     one belonging to somebody else, which are deliberately the same answer
     */
    @Transactional(readOnly = true)
    public PledgeDetail read(UUID pledgeId, UUID backerId) {
        return detailOf(pledges.findOwned(pledgeId, backerId)
                .orElseThrow(() -> new PledgeNotFoundException(pledgeId)));
    }

    /**
     * {@code POST /v1/pledges/{id}/confirm}: §6.2's {@code DRAFT --> CONFIRMED}.
     *
     * <p><strong>Two things happen, and they are one transaction.</strong> The held
     * place becomes a claimed one and the pledge becomes {@code CONFIRMED}. Either
     * alone is a lie: a confirmed pledge against a place still counted as merely
     * reserved is one the sweep would give away if it ever saw the row as a draft
     * again, and a claimed place against a pledge still in {@code DRAFT} is stock
     * nobody will ever release.
     *
     * <p><strong>The stock move is one statement</strong>, not a release followed by
     * a claim — {@code RewardTierRepository#commitOnePlace} carries the argument for
     * why the tier must never be momentarily short.
     *
     * <p><strong>What §9.2 says happens here and does not yet.</strong> Phase 1 of
     * card-on-file is a verification authorisation, 3-D Secure, storing the token and
     * the scheme transaction identifier, and voiding the authorisation. It is
     * {@link PledgeCapability#CARD_VERIFICATION} — #55, blocked on #60 — and it is
     * absent rather than stubbed. What §9.2 also says, and what makes the rest of
     * this correct without it, is that <em>no money moves at confirmation and no
     * ledger entry is written</em>: the charge is phase 2, at the close of a
     * successful campaign. So this transition commits a backer to a campaign, and
     * that is all it has ever done.
     *
     * <p>{@code paymentMethodId} is accepted and stored so that the shape a client
     * sends does not change when #55 lands. Nothing resolves it, nothing validates
     * it, and the response says {@code cardVerified: false} rather than letting a
     * client infer otherwise.
     *
     * @throws PledgeNotFoundException when the pledge is not this backer's
     * @throws PledgeNotDraftException when it is not in {@code DRAFT} — §10.4's
     *     {@code PLEDGE_NOT_DRAFT}
     * @throws ReservationExpiredException when its five minutes ran out — §10.4's
     *     {@code RESERVATION_EXPIRED}
     */
    @Transactional
    public PledgeDetail confirm(UUID pledgeId, UUID backerId, UUID paymentMethodId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Pledge pledge = pledges.findOwned(pledgeId, backerId)
                .orElseThrow(() -> new PledgeNotFoundException(pledgeId));

        if (!pledge.isDraft()) {
            throw new PledgeNotDraftException(pledgeId, pledge.getState());
        }
        if (pledge.hasLapsed(now)) {
            // The clock decides, not the state: §8.4's sweep runs every minute and
            // the backer does not, so a draft whose window closed forty seconds ago
            // is still DRAFT in the table. Confirming it would commit a place the
            // tier has already promised to give back. See ReservationExpiredException
            // for why it is not expired here as well.
            throw new ReservationExpiredException(pledgeId, pledge.getReservationExpiresAt());
        }

        if (pledge.holdsAPlace() && !stock.commitOnePlace(pledge.getRewardTierId())) {
            // The draft held a tier and the tier had no held place to convert. That
            // is an invariant violation rather than a race — this transaction has the
            // pledge and its version — so it is logged loudly and the confirmation
            // goes ahead. Refusing would strand a backer who did everything right in
            // front of a state only we can repair; the count is recoverable from the
            // pledges, and a lost commitment is not.
            log.error(
                    "Pledge {} was confirmed against reward tier {}, which had no reserved place to commit.",
                    pledgeId,
                    pledge.getRewardTierId());
        }

        // Where §9.2's phase 1 goes: authorise, 3-D Secure, store the token, void.
        // PledgeCapability.CARD_VERIFICATION — #55, blocked on #60.
        pledge.confirm(now, paymentMethodId);

        return detailOf(pledge);
    }

    /**
     * {@code PATCH /v1/pledges/{id}}: §4.5's PL-09, a backer changing their mind.
     *
     * <p>The whole {@code PledgeDetail} comes back, never the changed fields: a
     * client that merged a partial response would keep a stale total, which on this
     * endpoint is the number somebody is about to be charged.
     *
     * <p>Everything that touches money or stock is {@link ReservationService#edit} —
     * the re-quote and the move of the place, in that order and in one transaction.
     * What is here is the rule about <em>whether</em> the pledge may be changed at
     * all, which is {@link #requireEditable}, and the read of the add-on lines that
     * the edit resolves an absent {@code addons} field against.
     *
     * @throws PledgeNotFoundException when the pledge is not this backer's
     * @throws PledgeNotEditableException when its state has moved past editing
     * @throws ReservationExpiredException when it is a draft whose five minutes ran
     *     out
     * @throws az.ideanest.project.application.ProjectNotAcceptingPledgesException
     *     when the campaign has closed — §4.5's "until the deadline"
     */
    @Transactional
    public PledgeDetail edit(EditPledge command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Pledge pledge = pledges.findOwned(command.pledgeId(), command.backerId())
                .orElseThrow(() -> new PledgeNotFoundException(command.pledgeId()));

        requireEditable(pledge, now, true);

        return detailOf(reservations.edit(pledge, command, addons.findByPledge(pledge.getId())));
    }

    /**
     * {@code DELETE /v1/pledges/{id}}: §4.5's PL-10 and §6.2's
     * {@code CANCELED_BY_BACKER}.
     *
     * <p><strong>Cancelling an already-cancelled pledge succeeds.</strong> It is the
     * first thing checked, before the campaign and before the state, and it returns
     * without moving a count. A retried cancellation has to be safe: the ordinary
     * retry carries the same {@code Idempotency-Key} and never reaches this method,
     * but a client that lost its key and sent a fresh one is asking for a state the
     * pledge is already in, and answering that with a 409 — or worse, releasing a
     * second place — would punish it for the one failure the header exists to
     * forgive. "It is cancelled" is true either way, and it stays true after the
     * campaign has closed, which is why this comes before the campaign check as well.
     *
     * <p><strong>A lapsed draft may still be cancelled</strong>, unlike edited. The
     * backer is asking for the place to go back and the sweep is about to do the same
     * thing; refusing would be our scheduling getting in their way. Whichever of the
     * two gets there first wins outright —
     * {@code PledgeRepository#expireLapsedDraft} matches only a row still in
     * {@code DRAFT} — so the place cannot be released twice.
     *
     * <p>Nothing is refunded. §9.7: nothing was collected.
     *
     * @throws PledgeNotFoundException when the pledge is not this backer's
     * @throws PledgeNotEditableException when its state has moved past withdrawing
     * @throws az.ideanest.project.application.ProjectNotAcceptingPledgesException
     *     when the campaign has closed
     */
    @Transactional
    public void cancel(UUID pledgeId, UUID backerId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Pledge pledge = pledges.findOwned(pledgeId, backerId)
                .orElseThrow(() -> new PledgeNotFoundException(pledgeId));

        if (pledge.isCanceledByBacker()) {
            return;
        }

        requireEditable(pledge, now, false);
        reservations.cancel(pledge, now);
    }

    /**
     * <strong>The whole of "may this backer still change this pledge", in one
     * place.</strong>
     *
     * <p>§4.5's PL-09 says "until the deadline" and PL-10 says nothing about timing
     * at all, and neither is a complete rule on its own. Two facts decide it, they
     * are owned by two different modules, and this is the only method that composes
     * them.
     *
     * <ol>
     *   <li><strong>The pledge's state.</strong> {@code PledgeState.EDITABLE} — a
     *       {@code DRAFT} that is still being assembled, or a {@code CONFIRMED}
     *       pledge whose backer has changed their mind. That constant carries why the
     *       other ten states are out.
     *   <li><strong>The campaign.</strong> Launched, {@code LIVE}, and before its
     *       deadline — which is exactly {@link PledgeAcceptance}, the same question
     *       {@code POST /v1/pledges/draft} asks and the only part of the project
     *       module this one may see. "Until the deadline" is not a second rule that
     *       could drift from the checkout's; it is the checkout's rule, called again.
     * </ol>
     *
     * <p><strong>The campaign is checked last, and answered as
     * {@code PROJECT_NOT_LIVE}.</strong> The epic's contract puts a closed campaign
     * under {@code PLEDGE_NOT_EDITABLE}; this answers it with the code the draft
     * endpoint already gives, because one fact should have one answer across the four
     * endpoints that ask about it — and because that problem detail carries
     * {@code meta.deadline}, which is what lets a client say "this campaign ended on
     * Tuesday" instead of "you cannot do that". {@code PLEDGE_NOT_EDITABLE} is left
     * to mean what only it can mean: the pledge itself has moved on. The deviation is
     * recorded in the pull request rather than taken quietly.
     *
     * <p>The state is checked first because it is already loaded and because it is
     * the more specific answer: a {@code COLLECTED} pledge on a closed campaign is
     * better described by what happened to the pledge than by what happened to the
     * campaign.
     *
     * @param refuseALapsedDraft whether a draft past its window is refused. True for
     *     an edit, which would otherwise re-quote against a reservation that is
     *     already gone; false for a cancellation, which is asking for that same place
     *     to go back. See {@link #cancel}
     */
    private void requireEditable(Pledge pledge, Instant now, boolean refuseALapsedDraft) {
        if (!pledge.getState().isEditable()) {
            throw new PledgeNotEditableException(pledge.getId(), pledge.getState());
        }
        if (refuseALapsedDraft && pledge.hasLapsed(now)) {
            // The clock decides, not the state — confirm()'s reason, unchanged. A
            // draft whose window closed forty seconds ago is still DRAFT in the
            // table, and editing it would re-price a place the tier has already
            // promised to give back.
            throw new ReservationExpiredException(pledge.getId(), pledge.getReservationExpiresAt());
        }
        acceptance.requireAcceptingPledges(pledge.getProjectId());
    }

    /** The pledge with its add-on lines, read inside the transaction that loaded it. */
    private PledgeDetail detailOf(Pledge pledge) {
        return new PledgeDetail(pledge, addons.findByPledge(pledge.getId()));
    }
}
