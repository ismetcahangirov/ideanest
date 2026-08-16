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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 *   <li><strong>Stock on the add-ons.</strong> A place is taken for the reward tier
 *       and for nothing else. §4.5's PL-13 is about a limited reward's place, and
 *       reserving <em>n</em> places on an add-on needs a second conditional
 *       statement, a matching release, and a sweep that walks
 *       {@code pledge_addons} to give them back — a change to the expiry path
 *       rather than an addition to this one. Until then a limited add-on is quoted
 *       and not held, which is stated here rather than discovered when one
 *       oversells.
 * </ul>
 */
@Service
public class ReservationService {

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
    private final RewardStock stock;
    private final ReservationExpiry expiry;
    private final PledgeProperties properties;
    private final Clock clock;

    public ReservationService(
            PledgeRepository pledges,
            PledgeAddonRepository addons,
            RewardStock stock,
            ReservationExpiry expiry,
            PledgeProperties properties,
            Clock clock) {
        this.pledges = pledges;
        this.addons = addons;
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

        return pledges.save(Pledge.draft(projectId, backerId, rewardTierId, amount, currency, lapsesAt(now)));
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

        PledgeQuote quote = PledgeQuote.of(selectionFor(command));

        if (command.rewardTierId() != null) {
            takeThePlace(command.projectId(), command.rewardTierId());
        }

        // saveAndFlush, and the flush is load-bearing. `total_amount` is a generated
        // column, so its value only exists once the INSERT has run — and the response
        // is built inside this transaction. A plain save leaves the insert queued
        // until something forces it, which is a flush that may or may not happen
        // depending on whether a later query in this transaction touches `pledges`:
        // the add-on rows below do, so a pledge with add-ons would come back with a
        // total and one without add-ons would come back with null. The flush is also
        // what makes the pledge exist before the rows that reference it.
        Pledge pledge = pledges.saveAndFlush(Pledge.draft(new NewPledge(
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
     */
    private PledgeSelection selectionFor(DraftPledge command) {
        List<UUID> selected = command.selectedTierIds();

        Map<UUID, RewardStock.SelectableTier> tiers = new LinkedHashMap<>();
        for (RewardStock.SelectableTier tier :
                stock.selectionOf(command.projectId(), selected, command.shippingCountry())) {
            tiers.put(tier.rewardTierId(), tier);
        }
        for (UUID rewardTierId : selected) {
            if (!tiers.containsKey(rewardTierId)) {
                // Absent from the answer means "not this campaign's" — see
                // RewardStock.selectionOf, which leaves this distinction to the
                // caller because only the caller can say which selection went
                // missing.
                throw new UnknownRewardTierException(command.projectId(), rewardTierId);
            }
        }

        String currency = currencyOf(tiers.values());
        if (!currency.equals(command.contribution().currency())) {
            // Adding the two would produce a number in neither currency, and the
            // pledge has one currency column to record it in. Refused here rather
            // than in the quote so that the message names both.
            throw new IllegalArgumentException("This campaign is priced in " + currency
                    + " and the contribution is in " + command.contribution().currency());
        }

        QuotedLine reward = null;
        if (command.rewardTierId() != null) {
            RewardStock.SelectableTier tier = tiers.get(command.rewardTierId());
            if (command.contribution().amount().compareTo(tier.amount()) < 0) {
                throw new ContributionBelowRewardPriceException(
                        command.contribution().amount(), tier.amount(), tier.currency());
            }
            // Always one. §7.2 gives a pledge a single reward_tier_id, so two of a
            // tier is two pledges or it is an add-on, and both of those already have
            // a shape.
            reward = lineFor(tier, 1, command.shippingCountry());
        }

        List<QuotedLine> addonLines = new ArrayList<>(command.addons().size());
        for (DraftPledge.AddonSelection addon : command.addons()) {
            addonLines.add(lineFor(tiers.get(addon.rewardTierId()), addon.quantity(), command.shippingCountry()));
        }

        return new PledgeSelection(
                currency, command.shippingCountry(), reward, addonLines, command.contribution().amount());
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
