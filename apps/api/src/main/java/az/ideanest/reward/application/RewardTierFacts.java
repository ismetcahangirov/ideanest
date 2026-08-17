package az.ideanest.reward.application;

import az.ideanest.project.application.RewardFacts;
import az.ideanest.reward.domain.RewardTier;
import az.ideanest.reward.domain.ShippingRule;
import az.ideanest.reward.infrastructure.RewardTierRepository;
import az.ideanest.reward.infrastructure.ShippingRuleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * This module's answer to the questions other modules ask about reward tiers.
 *
 * <p>Two interfaces, pointing opposite ways, and both for the same reason — the
 * one that keeps the modules acyclic. {@link RewardFacts} is the project module's
 * completeness checklist, declared there and implemented here because the
 * alternative would be a cycle; see that interface. {@link RewardStock} is the
 * pledge module's reservation (#51), declared <em>here</em> because that module
 * already depends on this one and the alternative would again be a cycle.
 *
 * <p><strong>The stock methods are one statement each</strong>, and the statements
 * are in {@link RewardTierRepository} rather than here. That is deliberate: what
 * makes a reservation correct is a conditional {@code UPDATE} whose condition is
 * part of the statement, so there is nothing left for this class to decide and
 * nothing a caller could get wrong by calling it in the wrong order. §7.2 says the
 * stock columns are "written by the pledge module and by reservation, never by the
 * campaign editor" — written by, not owned by, which is why the writes are here,
 * where the table's constraints and its entity live.
 *
 * <p><strong>Secret and withdrawn tiers are counted.</strong> §5.3's two rules are
 * about what the campaign <em>offers</em>, not about what a visitor can see: a
 * secret tier is one somebody will be charged for through a private link, and a
 * tier whose availability window has closed can be reopened by editing one field.
 * Both are priced, both are the campaign's, and excluding either would let a
 * campaign pass the price floor by hiding the tier that fails it.
 *
 * <p>No authorisation here. Every caller of this has already been through
 * {@code ProjectAccess} — the checklist endpoint and the submission both load the
 * campaign before they ask — and a second check would be a second answer to a
 * question {@code ProjectAccess} exists to answer once.
 */
@Service
public class RewardTierFacts implements RewardFacts, RewardStock {

    private final RewardTierRepository rewards;
    private final ShippingRuleRepository shippingRules;

    public RewardTierFacts(RewardTierRepository rewards, ShippingRuleRepository shippingRules) {
        this.rewards = rewards;
        this.shippingRules = shippingRules;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BigDecimal> pricesOf(UUID projectId) {
        return rewards.findByProjectIdOrderBySortOrderAscCreatedAtAsc(projectId).stream()
                .map(RewardTier::getAmount)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The campaign is checked here rather than by the caller because a tier
     * belongs to one, and a pledge naming a tier from a different campaign is a row
     * V17's composite foreign key refuses anyway. Doing it in this order means the
     * client is told which mistake it made instead of being handed a constraint
     * violation.
     *
     * <p>Secret, withdrawn, and out-of-window tiers are all priced. Whether a
     * particular backer may select one is a question about a secret link and about
     * the campaign's state, and it is answered by the endpoint that already loaded
     * the campaign to render it (#52) — not by the method that hands out a price.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<RewardTierPrice> priceOf(UUID projectId, UUID rewardTierId) {
        return rewards.findById(rewardTierId)
                .filter(tier -> tier.getProjectId().equals(projectId))
                .map(tier -> new RewardTierPrice(tier.getId(), tier.getAmount(), tier.getCurrency()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>No transaction of its own beyond the one the caller is in, deliberately.
     * Taking the place and recording who took it are one unit of work — that is the
     * whole argument in V17 for keeping the reservation in the database — so this
     * must join the caller's transaction and not commit ahead of it. {@code REQUIRED}
     * is what does that, and it is the default.
     */
    @Override
    @Transactional
    public boolean reservePlaces(UUID rewardTierId, int places) {
        return rewards.reservePlaces(rewardTierId, requirePlaces(places)) == 1;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean releasePlaces(UUID rewardTierId, int places) {
        return rewards.releasePlaces(rewardTierId, requirePlaces(places)) == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code REQUIRED}, like the two above and for the same reason: moving a
     * place from held to claimed and moving the pledge from DRAFT to CONFIRMED are
     * one unit of work, and this committing ahead of the caller would leave a tier
     * with a claim against a pledge that never confirmed.
     */
    @Override
    @Transactional
    public boolean commitPlaces(UUID rewardTierId, int places) {
        return rewards.commitPlaces(rewardTierId, requirePlaces(places)) == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code REQUIRED}, like the others: taking the new place, giving the old one
     * back, and re-quoting the pledge are one unit of work, and this committing ahead
     * of the caller would leave a tier holding a claim for an edit that failed.
     */
    @Override
    @Transactional
    public boolean claimPlaces(UUID rewardTierId, int places) {
        return rewards.claimPlaces(rewardTierId, requirePlaces(places)) == 1;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean releaseClaimedPlaces(UUID rewardTierId, int places) {
        return rewards.releaseClaimedPlaces(rewardTierId, requirePlaces(places)) == 1;
    }

    /**
     * Refuses a move of no places, or of a negative number of them.
     *
     * <p><strong>An exception rather than a {@code false}</strong>, and the difference
     * matters: every caller of the five above reads {@code false} as "the tier has not
     * got them", which for a zero would be a lie the caller then reports to a backer
     * as {@code REWARD_SOLD_OUT}. A move of nothing is a bug in whoever computed the
     * quantity — {@code pledge_addons_quantity_is_positive} refuses the row it would
     * have come from — and a bug that answers 409 is a bug nobody finds.
     *
     * <p>It is checked here rather than in the statements because the statements would
     * not notice. A zero satisfies both guards and both limits, updates one row,
     * returns 1, and moves nothing at all; a negative release would <em>add</em>
     * places to a tier, which is the one direction no constraint in V7 bounds.
     */
    private static int requirePlaces(int places) {
        if (places < 1) {
            throw new IllegalArgumentException("A stock movement is of at least one place, not " + places);
        }
        return places;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two queries however many lines were selected: the tiers, and then every
     * shipping rule for those tiers in one read. Resolving the rate per tier would
     * be a query per add-on on the request a backer is waiting on.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SelectableTier> selectionOf(
            UUID projectId, Collection<UUID> rewardTierIds, String destinationCountry) {

        if (rewardTierIds.isEmpty()) {
            // Not merely an optimisation: `IN ()` is not valid SQL, and a pledge with
            // no reward and no add-ons is §4.5's PL-02 rather than a mistake.
            return List.of();
        }

        List<RewardTier> tiers = rewards.findSelected(projectId, rewardTierIds);
        Map<UUID, ShippingRate> rates = ratesTo(tiers, destinationCountry);

        return tiers.stream()
                .map(tier -> new SelectableTier(
                        tier.getId(),
                        tier.getAmount(),
                        tier.getCurrency(),
                        tier.getShippingType().isShipped(),
                        rates.get(tier.getId())))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<UUID> alternativesTo(UUID projectId, UUID rewardTierId, Instant at) {
        return rewards.findAvailableAlternatives(projectId, rewardTierId, at);
    }

    /**
     * The rate each tier charges to one destination, for the tiers that priced it.
     *
     * <p>A tier with no rule for the destination is absent rather than present with
     * a zero. The distinction is the whole of §7.2's "anywhere the creator has
     * priced": a missing rate on a shipped line is a refusal, and a zero would make
     * the creator pay the carrier out of their funding without either party
     * noticing.
     *
     * <p>No destination means no rates at all. That is not the same as a destination
     * nobody priced — a backer who has not said where it goes has not chosen
     * anything wrong — but both come out of the quote as the same refusal, because
     * both are "this cannot be posted yet".
     */
    private Map<UUID, ShippingRate> ratesTo(List<RewardTier> tiers, String destinationCountry) {
        if (destinationCountry == null || tiers.isEmpty()) {
            return Map.of();
        }
        String destination = destinationCountry.trim().toUpperCase(Locale.ROOT);

        Map<UUID, ShippingRate> rates = new HashMap<>();
        for (ShippingRule rule : shippingRules.findByRewardTiers(tiers.stream()
                .map(RewardTier::getId)
                .toList())) {
            if (destination.equals(rule.getCountryCode())) {
                rates.put(
                        rule.getRewardTierId(),
                        new ShippingRate(rule.getCountryCode(), rule.getAmount(), rule.getAdditionalItemAmount()));
            }
        }
        return rates;
    }
}
