package az.ideanest.reward.application;

import az.ideanest.shared.money.Money;
import java.util.Collection;
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
     * The same, for tiers named one by one rather than by the campaign they belong to.
     *
     * <p><strong>A second method rather than a loop at the call site, and #287 is what made
     * it necessary.</strong> {@code GET /v1/me/pledges} is a page of a backer's own pledges,
     * each on a different campaign, and each naming at most one tier. Asking
     * {@link #titlesOf} per row would be twenty round trips per page view — and twenty
     * whole campaigns' worth of tiers fetched to read twenty titles, on a campaign with
     * forty of them. That is exactly the argument {@code ProjectSummaries.summariesOf}
     * makes about its own batch form, arrived at from the other direction.
     *
     * <p>Both methods stay. A report about one campaign genuinely asks about that campaign,
     * and expressing it as a set of tier identifiers there would mean the caller had to
     * know the tiers before it could ask what they are called.
     *
     * <p>The identifiers are not authorised and do not need to be, for {@link #titlesOf}'s
     * reason: a caller holds them because a row it may already read names them, and a tier
     * title tells nobody anything that row has not already decided they may see.
     *
     * @param rewardTierIds the tiers. Null and empty are both an empty answer, and so is a
     *     tier that no longer exists — a campaign may delete a tier no pledge took, and a
     *     page of pledges must not fail because one identifier is stale
     * @return one entry per tier that exists, keyed by its identifier. Never null, possibly
     *     smaller than what was asked for
     */
    Map<UUID, RewardTitle> titlesOfTiers(Collection<UUID> rewardTierIds);

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
