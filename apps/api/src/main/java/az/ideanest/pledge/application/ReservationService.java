package az.ideanest.pledge.application;

import az.ideanest.pledge.PledgeProperties;
import az.ideanest.pledge.domain.NewPledge;
import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeAddon;
import az.ideanest.pledge.domain.PledgeQuote;
import az.ideanest.pledge.domain.PledgeSelection;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.pledge.domain.QuotedLine;
import az.ideanest.pledge.infrastructure.PledgeAddonRepository;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import az.ideanest.reward.application.RewardStock;
import az.ideanest.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds a limited reward's place for the backer who is checking out, for as long
 * as §4.5 gives them to finish.
 *
 * <p><strong>The reservation is a DRAFT pledge.</strong> §6.2 already says so —
 * {@code [*] -> DRAFT}, and {@code DRAFT -> EXPIRED: reservation TTL} — so what
 * this class creates is a row in {@code pledges} whose existence is the claim on
 * the stock. V17's header carries the argument for why that lives in PostgreSQL
 * rather than in the Redis key §4.5's capability table mentions; the short version
 * is that taking the place and recording who took it have to be one transaction,
 * and across two datastores they are not.
 *
 * <p><strong>What makes it correct is one statement, not this class.</strong>
 * {@link RewardStock#reserveOnePlace} is a conditional {@code UPDATE} that takes
 * PostgreSQL's row lock and re-reads the counts behind it, so two checkouts racing
 * for the last place are serialised by the database. Everything here is ordering
 * and error messages. If this code is wrong, V7's
 * {@code reward_tiers_stock_is_within_the_limit} refuses the transaction rather
 * than letting the tier oversell — which is the property {@code ReservationTests}
 * exercises with real threads against a real PostgreSQL, because it is the only
 * way to find out.
 *
 * <p><strong>The order of the three writes is deliberate.</strong> The caller's
 * own stale draft is settled first, then the place is taken, then the pledge is
 * inserted. Taking the place before inserting means a failure of the insert rolls
 * the place back inside the same transaction; inserting first would leave a window
 * in which a draft exists that holds nothing, and it is the drafts that the sweep
 * counts.
 *
 * <p><strong>#52 added {@link #draft}, and it is now the checkout's way in.</strong>
 * {@link #reserve} is what #51 built — a place, priced at the tier — and it remains
 * the smallest statement of what a reservation is, which is what
 * {@code ReservationTests} drives. The new method prices the whole of §4.5's PL-01
 * to PL-06 before it takes anything, and then takes the place and writes the row
 * through the same three steps. Both go through {@link #hold}; the difference
 * between them is only how much has been decided by the time the row is written.
 *
 * <p><strong>#56 added {@link #edit}, which is the only method here that moves a
 * place rather than taking or giving one.</strong> It takes the new place before
 * releasing the old, so that a backer whose edit is refused still holds what they
 * already had; that ordering is the single most important line in this class and its
 * javadoc carries the argument. It also re-quotes through the same
 * {@link #selectionFor} the checkout uses, so a draft and an edit of that draft
 * cannot come to two different totals for one selection.
 *
 * <p><strong>What is not here.</strong>
 *
 * <ul>
 *   <li><strong>Whether the campaign will take a pledge at all.</strong> A
 *       reservation on a campaign that is not live, or one past its deadline
 *       without late pledging enabled, is a request that should never reach this
 *       far. That is a question about {@code projects}, which is another module's
 *       state machine, and it belongs with the endpoint that has already loaded
 *       the campaign to render it (#52). Checking it here would mean this module
 *       depending on the project module in order to say no twice — so #52 put it
 *       in {@link PledgeService}, one call above this one, and this class still
 *       names nothing in {@code az.ideanest.project}.
 *   <li><strong>§10.3's {@code Idempotency-Key}.</strong> Recorded on the row, and
 *       enforced a layer above by {@code shared.idempotency}. Nothing here is
 *       replay-safe on its own, and it does not need to be: the machinery runs this
 *       at most once per key.
 *   <li><strong>Stock on the add-ons — #203.</strong> A place is taken for the
 *       reward tier and for nothing else. §4.5's PL-13 is about a limited reward's
 *       place, and reserving <em>n</em> places on an add-on needs a second
 *       conditional statement, a matching release, and a sweep that walks
 *       {@code pledge_addons} to give them back — a change to the expiry path
 *       rather than an addition to this one. Until then a limited add-on is quoted
 *       and not held, which is stated here rather than discovered when one
 *       oversells. <strong>#56's {@link #edit} makes the gap no wider and no
 *       narrower:</strong> changing an add-on's quantity moves no count, exactly as
 *       selecting it in the first place moved none, and cancelling releases nothing
 *       for it because nothing was held.
 * </ul>
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    /**
     * §7.2's "one pledge per backer per project", as PostgreSQL names it. V17 creates
     * it, {@code PledgeState.ACTIVE} is the set of states it covers, and it is the
     * only constraint on {@code pledges} this class translates into an answer.
     */
    private static final String ONE_ACTIVE_PLEDGE_PER_BACKER = "pledges_project_backer_active_key";

    /**
     * The only currency the platform settles in, for a pledge that has no reward
     * tier to take one from.
     *
     * <p>Duplicated from {@code RewardService}, and deliberately not shared, for
     * the reason stated there: the real answer is the campaign's own
     * {@code currency} column, which this module cannot read. When a second
     * currency exists, both constants are replaced by that lookup. A pledge that
     * <em>does</em> name a tier takes the currency off the tier, which is where the
     * price came from.
     */
    private static final String SUPPORTED_CURRENCY = "AZN";

    private final PledgeRepository pledges;
    private final PledgeAddonRepository addons;
    private final PledgeConflicts conflicts;
    private final RewardStock stock;
    private final ReservationExpiry expiry;
    private final PledgeProperties properties;
    private final Clock clock;

    public ReservationService(
            PledgeRepository pledges,
            PledgeAddonRepository addons,
            PledgeConflicts conflicts,
            RewardStock stock,
            ReservationExpiry expiry,
            PledgeProperties properties,
            Clock clock) {
        this.pledges = pledges;
        this.addons = addons;
        this.conflicts = conflicts;
        this.stock = stock;
        this.expiry = expiry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reserves a place on a reward tier and returns the draft pledge holding it.
     *
     * <p>The draft lapses at {@code now + ttl} and is released by §8.4's
     * {@code reservation-cleaner} — see {@link ReservationCleanerJob}. Nothing has
     * been charged and no card has been seen; that is confirmation's job (#52).
     *
     * @param rewardTierId null for §4.5's PL-02, a pledge that supports the
     *     campaign without taking a reward. Nothing is reserved in that case,
     *     because there is no limited thing to hold — the draft still expires, so
     *     that an abandoned checkout does not block the backer from starting
     *     another one for ever
     * @throws UnknownRewardTierException when the campaign has no such tier
     * @throws RewardSoldOutException when it has no places left — §10.4's
     *     {@code REWARD_SOLD_OUT}
     * @throws PledgeAlreadyExistsException when this backer already has a live
     *     pledge on this campaign. §7.2: one per backer per project
     */
    @Transactional
    public Pledge reserve(UUID projectId, UUID backerId, UUID rewardTierId) {
        // Truncated to what the column can hold: PostgreSQL stores microseconds, so
        // an expiry written at nanosecond precision comes back as a different
        // instant, and this one is compared against by a sweep.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        settleAnyExistingPledge(projectId, backerId, now);

        BigDecimal amount = BigDecimal.ZERO;
        String currency = SUPPORTED_CURRENCY;
        if (rewardTierId != null) {
            RewardStock.RewardTierPrice price = stock.priceOf(projectId, rewardTierId)
                    .orElseThrow(() -> new UnknownRewardTierException(projectId, rewardTierId));
            amount = price.amount();
            currency = price.currency();

            takeThePlace(projectId, rewardTierId);
        }

        return insert(Pledge.draft(projectId, backerId, rewardTierId, amount, currency, lapsesAt(now)));
    }

    /**
     * A whole checkout: prices §4.5's PL-01 to PL-06, takes the place, and writes the
     * draft that holds it. #52.
     *
     * <p><strong>Priced before anything is taken.</strong> The quote is arithmetic
     * over reads, so doing it first costs nothing and means a selection that cannot
     * be quoted — a destination the creator never priced, a contribution below the
     * tier — never holds a place while the backer is told about it. After that the
     * order is #51's, unchanged and for #51's reason: take the place, then insert the
     * row, so that a failure of the insert rolls the place back inside the same
     * transaction.
     *
     * <p>The add-on lines are written last because they reference the pledge, and
     * they are written at all because {@code addons_amount} is a sum: see
     * {@code PledgeAddon} for what needs the lines and V18 for why the table is an
     * addition to §7.2 rather than a reading of it.
     *
     * <p>Nothing here is charged and no card has been seen. That is confirmation's,
     * and confirmation does not charge anything either — §9.2.
     *
     * @throws UnknownRewardTierException when the campaign has no such tier, whether
     *     it was named as the reward or as an add-on
     * @throws ContributionBelowRewardPriceException when the backer offered less than
     *     the reward costs
     * @throws ShippingDestinationUnpricedException when something in the selection is
     *     posted and the destination has no rate
     * @throws RewardSoldOutException when the tier has no places left — §10.4's
     *     {@code REWARD_SOLD_OUT}
     * @throws PledgeAlreadyExistsException when this backer already has a live pledge
     *     on this campaign. §7.2: one per backer per project
     */
    @Transactional
    public Pledge draft(DraftPledge command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        settleAnyExistingPledge(command.projectId(), command.backerId(), now);

        PledgeQuote quote = PledgeQuote.of(selectionFor(
                command.projectId(),
                command.rewardTierId(),
                command.addons(),
                command.contribution(),
                command.shippingCountry()));

        if (command.rewardTierId() != null) {
            takeThePlace(command.projectId(), command.rewardTierId());
        }

        Pledge pledge = insert(Pledge.draft(new NewPledge(
                command.projectId(),
                command.backerId(),
                command.rewardTierId(),
                quote,
                command.shippingCountry(),
                command.anonymous(),
                command.referrerCode(),
                command.idempotencyKey(),
                lapsesAt(now))));

        if (!command.addons().isEmpty()) {
            addons.saveAll(command.addons().stream()
                    .map(addon -> PledgeAddon.of(
                            pledge.getId(), command.projectId(), addon.rewardTierId(), addon.quantity()))
                    .toList());
        }
        return pledge;
    }

    /**
     * §4.5's PL-09: a new selection on a pledge that already exists, re-quoted, with
     * its place moved if the reward changed. #56.
     *
     * <p><strong>Priced, then the place is moved, then the row is written.</strong>
     * The same order as {@link #draft} and for a sharper version of its reason: a
     * selection that cannot be quoted — a destination the creator never priced, a
     * contribution below the new tier — must not cost the backer the place they
     * already hold, and pricing first means it never can.
     *
     * <p><strong>Take the new place before giving the old one back. This is the
     * whole of the hard part.</strong> The other order is the tempting one, because
     * it keeps {@code claimed + reserved} from rising, and it is wrong in the way
     * that matters most: between the release and the take, the backer holds nothing,
     * and if the take then fails — the new tier sold its last place a moment ago —
     * they have lost the reward they already had in exchange for one they never got.
     * Taking first means the worst case is a refusal that changes nothing, which is
     * what a failed edit ought to be. The cost is that a backer switching between two
     * tiers momentarily holds a place on each, so a tier can refuse a switch it would
     * have allowed had the release come first; that is a refusal somebody can retry,
     * and losing a reservation is not.
     *
     * <p>The whole method is one transaction, so the take, the release, the re-quote
     * and the add-on rows either all happen or none do. That is what lets the
     * refusal above be a genuine no-op rather than a promise.
     *
     * <p><strong>Which column moves depends on the pledge, not on the tier.</strong>
     * A {@code DRAFT} holds a <em>reserved</em> place and a {@code CONFIRMED} pledge
     * holds a <em>claimed</em> one, so an edit moves like for like — see
     * {@link RewardStock#claimOnePlace} and
     * {@link RewardStock#releaseOneClaimedPlace}. Moving a confirmed backer's place
     * through {@code reserved_quantity} would leave a reservation against a pledge
     * that is not a draft, which is exactly the row §8.4's sweep hunts for.
     *
     * <p><strong>Nothing is reserved on the add-ons, and this method makes that gap
     * no wider and no narrower.</strong> A limited add-on is quoted and not held —
     * see this class's header and #203 — so changing an add-on's quantity moves no
     * count at all, exactly as selecting it in the first place did not.
     *
     * @param pledge the backer's own pledge, already established to be theirs and to
     *     be in a state they may change. This method does not re-decide either: see
     *     {@link PledgeService#edit}, which is where the campaign's deadline and
     *     §6.2's states are composed into one rule
     * @param existingAddons the add-on lines as they stand, read by the caller inside
     *     this transaction. Passed in rather than read again because they are needed
     *     twice — to resolve an absent {@code addons} field, and to decide whether
     *     the rows have to be rewritten at all
     * @throws UnknownRewardTierException when the campaign has no such tier
     * @throws ContributionBelowRewardPriceException when the backer offered less than
     *     the reward now costs
     * @throws ShippingDestinationUnpricedException when the new selection is posted
     *     and the destination has no rate
     * @throws RewardSoldOutException when the newly chosen tier has no places left —
     *     §10.4's {@code REWARD_SOLD_OUT}, and the pledge is left exactly as it was
     */
    @Transactional
    public Pledge edit(Pledge pledge, EditPledge command, List<PledgeAddon> existingAddons) {
        UUID projectId = pledge.getProjectId();

        UUID rewardTierId =
                command.rewardTierId().isPresent() ? command.rewardTierId().value() : pledge.getRewardTierId();
        String shippingCountry = command.shippingCountry().isPresent()
                ? command.shippingCountry().value()
                : pledge.getShippingCountry();
        boolean anonymous =
                command.anonymous().isPresent() ? command.anonymous().value() : pledge.isAnonymous();
        UUID paymentMethodId = command.paymentMethodId().isPresent()
                ? command.paymentMethodId().value()
                : pledge.getPaymentMethodId();
        List<DraftPledge.AddonSelection> addonSelections = selectedAddons(command, existingAddons);
        Money contribution =
                command.contribution().isPresent() ? command.contribution().value() : contributionOf(pledge);

        // The same rule the checkout applies, from the same method, against a
        // selection the checkout never saw. See DraftPledge.
        DraftPledge.requireDistinctSelections(rewardTierId, addonSelections);

        PledgeQuote quote = PledgeQuote.of(
                selectionFor(projectId, rewardTierId, addonSelections, contribution, shippingCountry));

        moveThePlace(pledge, rewardTierId);

        pledge.edit(quote, rewardTierId, shippingCountry, anonymous, paymentMethodId);
        replaceAddons(pledge, existingAddons, addonSelections);

        // saveAndFlush for draft's second reason: total_amount is generated, so the
        // response cannot be built until the UPDATE has run. The version check that
        // comes with it is the point of the flush being here rather than at the
        // commit — a backer editing in one tab while another confirms is exactly the
        // race Pledge's @Version exists for, and it has to surface as an answer
        // rather than at a commit nothing can translate.
        return pledges.saveAndFlush(pledge);
    }

    /**
     * §4.5's PL-10: the backer withdraws, and the place goes back to the tier. #56.
     *
     * <p><strong>Which place goes back depends on what the pledge was holding.</strong>
     * A {@code DRAFT} gives back a <em>reserved</em> place and a {@code CONFIRMED}
     * pledge gives back a <em>claimed</em> one. They are different statements against
     * different columns, and releasing the wrong one leaves the tier counting a place
     * nobody holds while it is short of one somebody does — with the sum, which is
     * what the limit is checked against, still looking correct.
     *
     * <p><strong>Nothing is refunded, because nothing was collected.</strong> §9.7's
     * row for this case says so in terms, and §9.2 puts the only collection at the
     * close of a successful campaign. There is no transaction to reverse and no
     * ledger entry to write; the refund of a pledge that <em>was</em> collected is
     * #67's.
     *
     * <p>The state change and the credit are one transaction, for
     * {@link ReservationExpiry}'s reason: a pledge that says cancelled while the tier
     * still counts its place is a place nothing will ever release.
     *
     * <p>Nothing is released for the add-ons, because nothing was ever held for them
     * — see this class's header and #203.
     *
     * @param pledge the backer's own pledge, already established to be theirs and to
     *     be in a state they may withdraw. See {@link PledgeService#cancel}
     */
    @Transactional
    public void cancel(Pledge pledge, Instant now) {
        UUID held = pledge.getRewardTierId();
        // Read before the transition, because it is the old state that says which
        // column the place is counted in.
        boolean committed = pledge.isConfirmed();

        pledge.cancelByBacker(now);
        if (held != null) {
            releaseTheHeldPlace(pledge, held, committed);
        }
        pledges.saveAndFlush(pledge);
    }

    /**
     * The add-on selection after the edit: the one that was sent, or the one already
     * stored.
     *
     * <p>{@code "addons": null} is an empty selection — a backer who removed all of
     * them — and an absent field leaves them alone. Collapsing those two would make
     * an edit of the contribution alone silently strip every extra off the pledge.
     */
    private static List<DraftPledge.AddonSelection> selectedAddons(
            EditPledge command, List<PledgeAddon> existingAddons) {

        if (!command.addons().isPresent()) {
            return existingAddons.stream()
                    .map(addon -> new DraftPledge.AddonSelection(addon.getRewardTierId(), addon.getQuantity()))
                    .toList();
        }
        List<DraftPledge.AddonSelection> sent = command.addons().value();
        return sent == null ? List.of() : List.copyOf(sent);
    }

    /**
     * What the backer chose to give, reconstructed from the pledge when the edit does
     * not say.
     *
     * <p><strong>Base plus bonus, which is the definition rather than a
     * reconstruction.</strong> {@code PledgeQuote} splits a contribution into the
     * tier's price and the support above it, so adding the two back together returns
     * the number the backer actually chose — and it does so for a support-only pledge
     * as well, where the base is the whole of it and the bonus is zero. Add-ons,
     * shipping and tax are excluded because they were never part of it.
     *
     * <p><strong>When the tier changes and the contribution does not</strong>, the
     * backer's old number is quoted against the new tier's price. Moving to something
     * cheaper leaves the difference as PL-03 bonus support, which is what they are
     * still paying and what they will still be charged. Moving to something dearer is
     * refused with {@code CONTRIBUTION_BELOW_REWARD_PRICE} rather than silently
     * raised — quietly charging somebody more for a tier they upgraded to is the one
     * outcome no client could defend, and the refusal names both amounts so the
     * client can offer the new figure.
     */
    private static Money contributionOf(Pledge pledge) {
        return Money.of(pledge.getBaseAmount().add(pledge.getBonusAmount()), pledge.getCurrency());
    }

    /**
     * Moves the pledge's place from the tier it held to the tier it now names.
     *
     * <p>Nothing happens when the reward did not change, which is the common edit —
     * a new destination, another add-on, a bigger contribution. A pledge that had no
     * reward and still has none moves nothing either.
     *
     * @throws RewardSoldOutException when the new tier is full. Thrown <em>before</em>
     *     the old place is given back, so the transaction rolls back with the backer
     *     still holding exactly what they had
     */
    private void moveThePlace(Pledge pledge, UUID rewardTierId) {
        UUID held = pledge.getRewardTierId();
        if (Objects.equals(held, rewardTierId)) {
            return;
        }

        // Read before the state changes, because it decides which of the two columns
        // this pledge's place lives in.
        boolean committed = pledge.isConfirmed();

        if (rewardTierId != null) {
            boolean taken = committed ? stock.claimOnePlace(rewardTierId) : stock.reserveOnePlace(rewardTierId);
            if (!taken) {
                throw new RewardSoldOutException(pledge.getProjectId(), rewardTierId);
            }
        }
        if (held != null) {
            releaseTheHeldPlace(pledge, held, committed);
        }
    }

    /**
     * Gives back the place a pledge was holding, from whichever column was holding it.
     *
     * <p>A tier with nothing to give back is logged rather than refused, for
     * {@link ReservationExpiry}'s reason: this transaction owns the pledge, so a
     * mismatch is an invariant violation rather than a race, and refusing would
     * strand a backer in front of a state only we can repair. The count is
     * recoverable from the pledges; a lost edit or a lost cancellation is not.
     */
    private void releaseTheHeldPlace(Pledge pledge, UUID rewardTierId, boolean committed) {
        boolean released =
                committed ? stock.releaseOneClaimedPlace(rewardTierId) : stock.releaseOnePlace(rewardTierId);
        if (!released) {
            log.error(
                    "Pledge {} held a {} place on reward tier {} that the tier had no record of.",
                    pledge.getId(),
                    committed ? "claimed" : "reserved",
                    rewardTierId);
        }
    }

    /**
     * Rewrites the add-on lines, and only when they actually changed.
     *
     * <p><strong>Compared before it is written, because the common edit changes
     * nothing here.</strong> A backer raising their contribution or hiding their name
     * sends the same add-ons back, and deleting and re-inserting identical rows would
     * be two statements and a composite foreign key check for no change at all.
     *
     * <p>When they have changed, the set is replaced wholesale — V18 says so, and
     * {@code EditPledge} carries the argument for why a per-line patch is the wrong
     * shape. The rows are removed as entities rather than by a bulk {@code DELETE}:
     * a bulk statement leaves the persistence context still holding the rows it
     * deleted, and the insert that follows would then be turned into an {@code
     * UPDATE} of a row that no longer exists. The flush between the two is what lets
     * a tier that survived the edit be written again under the primary key it just
     * gave up.
     */
    private void replaceAddons(
            Pledge pledge, List<PledgeAddon> existingAddons, List<DraftPledge.AddonSelection> selections) {

        Map<UUID, Integer> before = new LinkedHashMap<>();
        for (PledgeAddon addon : existingAddons) {
            before.put(addon.getRewardTierId(), addon.getQuantity());
        }
        Map<UUID, Integer> after = new LinkedHashMap<>();
        for (DraftPledge.AddonSelection selection : selections) {
            after.put(selection.rewardTierId(), selection.quantity());
        }
        if (before.equals(after)) {
            return;
        }

        if (!existingAddons.isEmpty()) {
            addons.deleteAll(existingAddons);
            addons.flush();
        }
        if (!selections.isEmpty()) {
            addons.saveAll(selections.stream()
                    .map(selection -> PledgeAddon.of(
                            pledge.getId(), pledge.getProjectId(), selection.rewardTierId(), selection.quantity()))
                    .toList());
        }
    }

    /**
     * Turns what the backer named into what {@link PledgeQuote} can add up.
     *
     * <p>One read of the reward module for every line at once — the reward and each
     * add-on, with the rate for the destination already resolved. Asking per line
     * would be a query per add-on plus one per rate table, on the request somebody is
     * waiting on with their card out.
     *
     * <p><strong>The two refusals here are also refusals in {@link PledgeQuote},
     * and that is deliberate.</strong> That class is arithmetic with no HTTP in it,
     * so it can only throw a sentence; §10.4 needs a code the client branches on and
     * a {@code meta} naming which tier and which country. So the checkout refuses
     * first, where those are known, and the quote's own refusal stays as the backstop
     * that makes it impossible to price a pledge nobody costed — including for
     * whatever calls it next.
     *
     * <p><strong>Taken apart from {@link DraftPledge} by #56</strong>, which quotes a
     * selection that was never one: an edit resolves a partial body against a stored
     * pledge, and what comes out is a reward, some add-ons, a contribution and a
     * destination with no command object around them. Both callers price through this
     * method, so a draft and an edit of that same draft cannot come to two different
     * totals for one selection — which is the only property that matters here, and it
     * is exactly what a second copy of this method would have quietly given up.
     */
    private PledgeSelection selectionFor(
            UUID projectId,
            UUID rewardTierId,
            List<DraftPledge.AddonSelection> addonSelections,
            Money contribution,
            String shippingCountry) {

        List<UUID> selected = new ArrayList<>(addonSelections.size() + 1);
        if (rewardTierId != null) {
            selected.add(rewardTierId);
        }
        addonSelections.stream().map(DraftPledge.AddonSelection::rewardTierId).forEach(selected::add);

        Map<UUID, RewardStock.SelectableTier> tiers = new LinkedHashMap<>();
        for (RewardStock.SelectableTier tier : stock.selectionOf(projectId, selected, shippingCountry)) {
            tiers.put(tier.rewardTierId(), tier);
        }
        for (UUID selectedTierId : selected) {
            if (!tiers.containsKey(selectedTierId)) {
                // Absent from the answer means "not this campaign's" — see
                // RewardStock.selectionOf, which leaves this distinction to the
                // caller because only the caller can say which selection went
                // missing.
                throw new UnknownRewardTierException(projectId, selectedTierId);
            }
        }

        String currency = currencyOf(tiers.values());
        if (!currency.equals(contribution.currency())) {
            // Adding the two would produce a number in neither currency, and the
            // pledge has one currency column to record it in. Refused here rather
            // than in the quote so that the message names both.
            throw new IllegalArgumentException("This campaign is priced in " + currency
                    + " and the contribution is in " + contribution.currency());
        }

        QuotedLine reward = null;
        if (rewardTierId != null) {
            RewardStock.SelectableTier tier = tiers.get(rewardTierId);
            if (contribution.amount().compareTo(tier.amount()) < 0) {
                throw new ContributionBelowRewardPriceException(
                        contribution.amount(), tier.amount(), tier.currency());
            }
            // Always one. §7.2 gives a pledge a single reward_tier_id, so two of a
            // tier is two pledges or it is an add-on, and both of those already have
            // a shape.
            reward = lineFor(tier, 1, shippingCountry);
        }

        List<QuotedLine> addonLines = new ArrayList<>(addonSelections.size());
        for (DraftPledge.AddonSelection addon : addonSelections) {
            addonLines.add(lineFor(tiers.get(addon.rewardTierId()), addon.quantity(), shippingCountry));
        }

        return new PledgeSelection(currency, shippingCountry, reward, addonLines, contribution.amount());
    }

    /**
     * One selected tier as a line of the quote.
     *
     * <p>A shipped line with no rate for the destination is refused rather than
     * quoted at zero — including when there is no destination at all. §7.2's
     * "anywhere the creator has priced" is the rule, and a zero would make the
     * creator pay the carrier out of their own funding, silently.
     */
    private QuotedLine lineFor(RewardStock.SelectableTier tier, int quantity, String destination) {
        if (!tier.shipped()) {
            return QuotedLine.notShipped(tier.amount(), tier.currency(), quantity);
        }
        if (destination == null || tier.shippingRate() == null) {
            throw new ShippingDestinationUnpricedException(tier.rewardTierId(), destination);
        }
        return QuotedLine.shipped(tier.amount(), tier.currency(), quantity, tier.shippingRate());
    }

    /**
     * The campaign's currency, taken from what the backer selected.
     *
     * <p>A tier carries its campaign's currency (§7.2), so any selected tier answers
     * this. A pledge that selected nothing — PL-02, support only — has nothing to
     * take it from, and falls back to the one currency the platform settles in for
     * the reason given on {@link #SUPPORTED_CURRENCY}.
     *
     * <p>Tiers disagreeing with each other is not decided here: {@link PledgeQuote}
     * refuses a selection spanning two currencies, and it is the right place for it
     * because that is where the amounts would be added together.
     */
    private static String currencyOf(Iterable<RewardStock.SelectableTier> tiers) {
        for (RewardStock.SelectableTier tier : tiers) {
            return tier.currency();
        }
        return SUPPORTED_CURRENCY;
    }

    /**
     * Writes the draft, and turns the one race the read above cannot win into the
     * same refusal the read gives.
     *
     * <p><strong>{@code settleAnyExistingPledge} is a read, and a read cannot decide
     * this.</strong> Two checkouts by one backer arriving together both find no
     * pledge, both conclude the backer has none, and both insert;
     * {@code pledges_project_backer_active_key} refuses the second. That is not an
     * exotic race — it is somebody double-clicking a button, or a client retrying
     * with a fresh idempotency key instead of the one it already had — and before
     * this it reached the backer as a 500, which tells them nothing and pages
     * somebody. The read decides what to say; the index decides what is true, and
     * this is where the index gets to say it.
     *
     * <p><strong>{@code saveAndFlush}, so the statement runs here.</strong> A plain
     * {@code save} queues the insert until something forces it — possibly the commit,
     * which is outside this method and outside anything that could translate it. The
     * flush is load-bearing for a second reason: {@code total_amount} is a generated
     * column whose value only exists once the {@code INSERT} has run, and the
     * response is built inside this transaction.
     *
     * <p>The campaign and the backer are read off the draft rather than passed in, so
     * that what is looked up afterwards cannot describe a different row from the one
     * that was refused.
     *
     * @throws PledgeAlreadyExistsException when the index refused this insert because
     *     the backer already has a live pledge on the campaign — §7.2
     */
    private Pledge insert(Pledge draft) {
        try {
            return pledges.saveAndFlush(draft);
        } catch (DataIntegrityViolationException violation) {
            throw refusalFor(violation, draft.getProjectId(), draft.getBackerId());
        }
    }

    /**
     * The answer for a violated constraint, or the violation itself when it is one
     * this class has nothing to say about.
     *
     * <p><strong>One constraint by name, and nothing broader — that is the whole
     * point.</strong> Catching {@code DataIntegrityViolationException} and calling
     * every one of them "you already have a pledge" would be a comfortable lie: the
     * other constraint reachable from this transaction is V7's
     * {@code reward_tiers_stock_is_within_the_limit}, and reporting an oversold
     * reward as a duplicate pledge would hide the one failure this module exists to
     * make impossible, behind a message plausible enough that nobody would look. The
     * same goes for the composite reference to {@code reward_tiers}, the state check,
     * and the idempotency index: each of those means a bug somewhere, and a bug that
     * answers 409 is a bug nobody finds.
     *
     * <p>So the constraint is matched by name and everything else is rethrown
     * untouched — a 500, loudly, exactly as it behaves today.
     */
    private RuntimeException refusalFor(
            DataIntegrityViolationException violation, UUID projectId, UUID backerId) {

        if (!ONE_ACTIVE_PLEDGE_PER_BACKER.equals(violatedConstraintOf(violation))) {
            return violation;
        }

        // Read outside this transaction, which the violation has already doomed —
        // see PledgeConflicts. The pledge that refused this insert is committed by
        // definition, so this normally finds it, and the backer gets the identifier
        // of the pledge they already have exactly as the read path gives it: they
        // cannot tell which of the two refused them, and should not be able to.
        Pledge existing = conflicts.activePledgeOf(projectId, backerId).orElse(null);
        if (existing == null) {
            // The pledge that won the race stopped being active in the moment since —
            // cancelled, or a draft the sweep expired. "You already have a pledge" is
            // no longer true and there is no identifier to hand over, so nothing
            // truthful can be said and the violation is not dressed up as an answer.
            // A retry succeeds, because the read at the top of the checkout will find
            // nothing too.
            log.error(
                    "Pledge insert for backer {} on campaign {} was refused by {}, and no active pledge explains it.",
                    backerId,
                    projectId,
                    ONE_ACTIVE_PLEDGE_PER_BACKER);
            return violation;
        }
        return new PledgeAlreadyExistsException(projectId, backerId, existing.getId(), existing.getState());
    }

    /**
     * Which constraint PostgreSQL refused, as it named it.
     *
     * <p>Spring wraps the driver's error in a {@code DataIntegrityViolationException}
     * that says only that <em>something</em> was violated, which is why the name has
     * to be dug out: Hibernate's own exception carries it, extracted by the
     * PostgreSQL dialect from the message the server sent.
     *
     * @return null when the cause chain holds no Hibernate constraint violation, or
     *     when it holds one that could not be named — both of which are "this is not
     *     the constraint we are looking for"
     */
    private static String violatedConstraintOf(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernate) {
                return hibernate.getConstraintName();
            }
        }
        return null;
    }

    /**
     * Takes the place, or says why it could not.
     *
     * <p>One conditional {@code UPDATE} — see {@link RewardStock#reserveOnePlace}.
     * Somebody taking the last place while this backer was reading the page is the
     * ordinary case, not an error, and §10.4 answers it with the alternatives that
     * are left rather than with a bare refusal.
     */
    private void takeThePlace(UUID projectId, UUID rewardTierId) {
        if (!stock.reserveOnePlace(rewardTierId)) {
            throw new RewardSoldOutException(projectId, rewardTierId);
        }
    }

    /** §4.5's five minutes, from the injected clock and the configured TTL. */
    private Instant lapsesAt(Instant now) {
        return now.plus(properties.reservation().ttl());
    }

    /**
     * Deals with the pledge this backer already has on this campaign, if any.
     *
     * <p>Two outcomes, and the difference matters to whoever is standing at the
     * checkout. A pledge that is still live — a draft with time left, or a
     * confirmed pledge — is §7.2's one-per-backer rule and they are told about it.
     * A draft whose five minutes have run out is released here and now, rather than
     * when the sweep next runs: the backer is the person whose reservation it was,
     * and making them wait up to a minute to be allowed to try again would be a
     * refusal caused entirely by our own scheduling.
     *
     * <p>Racing another request that is doing the same thing is safe in both
     * branches. The release is a conditional update that exactly one caller wins,
     * and if this read misses a pledge that another transaction is inserting,
     * {@code pledges_project_backer_active_key} refuses the second insert. This
     * read decides what to say; the index decides what is true.
     *
     * <p><strong>And when the index is the one that decides, it says the same
     * thing.</strong> {@link #insert} translates that one constraint into the same
     * {@link PledgeAlreadyExistsException} raised here, so a backer who
     * double-clicked cannot tell which of the two paths refused them. Before that
     * translation the race reached them as a 500.
     */
    private void settleAnyExistingPledge(UUID projectId, UUID backerId, Instant now) {
        Optional<Pledge> existing = pledges.findActive(projectId, backerId, PledgeState.ACTIVE);
        if (existing.isEmpty()) {
            return;
        }

        Pledge pledge = existing.get();
        if (!pledge.hasLapsed(now)) {
            throw new PledgeAlreadyExistsException(projectId, backerId, pledge.getId(), pledge.getState());
        }
        expiry.release(pledge.getId(), now);
    }
}
