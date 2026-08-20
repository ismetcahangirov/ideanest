package az.ideanest.reward.application;

import az.ideanest.shared.money.Money;
import java.util.Map;
import java.util.UUID;

/**
 * "What is this campaign's tier called, and what does it ask for?" — the one question the
 * backer report has about rewards.
 *
 * <p><strong>Declared here rather than in the module that asks.</strong> {@link RewardStock}
 * makes the argument: the pledge module already depends on this one, so an interface
 * declared there and implemented here would be a cycle. This is the same shape, for the
 * same reason, with a different question.
 *
 * <p><strong>And it is an interface rather than a join.</strong> §4.7's CD-10 needs a tier's
 * title beside every backer, and the cheap way to get one is
 * {@code LEFT JOIN reward_tiers} from the pledge module's own query. That reads a table this
 * module owns, which is the coupling {@code ModuleBoundaryTests} exists to prevent one layer
 * up: a column renamed here would break a statement in a module that never mentioned this
 * one. One extra indexed read per report is what the boundary costs, and it is bounded by
 * the number of tiers a campaign has rather than by the number of backers.
 *
 * <p><strong>Every tier, including the secret and the withdrawn.</strong> {@link RewardTierFacts}
 * gives the general reason — those rules are about what a campaign offers, not about what a
 * visitor may see — and the sharp version here is that a backer took the tier: leaving it out
 * would show their pledge as though it named no reward at all.
 */
public interface RewardTitles {

    /**
     * Every tier on a campaign, by identifier.
     *
     * <p>A map rather than a list, because the caller has pledges in hand and needs a
     * lookup; and the whole campaign rather than a requested subset, because a report page
     * of fifty backers can name most of a campaign's tiers anyway and the second shape
     * would be a query whose parameter list grows with the page.
     *
     * @return an empty map for a campaign with no tiers, and for one that does not exist.
     *     No authorisation happens here — every caller has already been through
     *     {@code ProjectAccess}, and a tier title tells nobody anything the report has not
     *     already decided they may see
     */
    Map<UUID, RewardTitle> titlesOf(UUID projectId);

    /**
     * What a tier is called and what it asks for.
     *
     * @param price the tier's own price, in the tier's own currency. Never the campaign's
     *     by assumption: §7.3 makes them the same, and this is the one place that would
     *     show if they ever were not
     */
    record RewardTitle(UUID rewardTierId, String title, Money price) {
    }
}
