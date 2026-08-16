package az.ideanest.reward.application;

import az.ideanest.project.application.RewardFacts;
import az.ideanest.reward.domain.RewardTier;
import az.ideanest.reward.infrastructure.RewardTierRepository;
import java.math.BigDecimal;
import java.util.List;
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

    public RewardTierFacts(RewardTierRepository rewards) {
        this.rewards = rewards;
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
    public boolean reserveOnePlace(UUID rewardTierId) {
        return rewards.reserveOnePlace(rewardTierId) == 1;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean releaseOnePlace(UUID rewardTierId) {
        return rewards.releaseOnePlace(rewardTierId) == 1;
    }
}
