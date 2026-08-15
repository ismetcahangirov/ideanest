package az.ideanest.reward.application;

import az.ideanest.project.application.RewardFacts;
import az.ideanest.reward.domain.RewardTier;
import az.ideanest.reward.infrastructure.RewardTierRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * This module's answer to the completeness checklist's two questions about
 * rewards.
 *
 * <p>The implementation of {@link RewardFacts}, which the project module declares
 * because it is the module that needs the answer — see that interface for why the
 * dependency points this way and not the other.
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
public class RewardTierFacts implements RewardFacts {

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
}
