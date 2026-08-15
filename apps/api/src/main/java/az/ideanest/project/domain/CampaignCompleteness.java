package az.ideanest.project.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything {@link SubmissionChecklist} needs to know about a campaign, and
 * nothing else.
 *
 * <p><strong>Why the rules take a record instead of a campaign.</strong> Two of
 * §5.3's requirements are about reward tiers, which live in another module. A
 * checklist that took a {@link Project} would still have to be handed the tiers
 * from somewhere, and a checklist that reached for a repository would stop being
 * the kind of type {@link ProjectStateMachine} is — a rule with no Spring and no
 * database, asserted against the specification in a plain unit test. So the
 * gathering is an application service's job and the judging is this package's,
 * which also means every one of these tests is a record literal rather than a
 * fixture, a container, and a transaction.
 *
 * <p><strong>Why reward facts arrive as numbers rather than as tiers.</strong>
 * {@code RewardTier} belongs to {@code az.ideanest.reward.domain}, which this
 * module may not name — {@code ModuleBoundaryTests} enforces it, and the
 * dependency would additionally be a cycle, because the reward module already
 * depends on this one for authorisation. What crosses the boundary is therefore a
 * count and a list of prices, gathered through a port this module owns
 * ({@code RewardFacts}) and implemented on the reward side. §5.3 asks two
 * questions about tiers — how many, and how cheap — and these are the answers to
 * exactly those.
 *
 * <p>Every field is what the campaign holds <em>now</em>, unvalidated. The
 * checklist's whole job is to judge it, so a record that refused a null title
 * would refuse the campaigns it exists to report on.
 *
 * @param title as stored, which is never null on a saved campaign but is checked
 *     anyway: this type is also constructed in tests and by a future importer
 * @param summary the {@code blurb} column
 * @param categoryId null until the campaign is filed
 * @param subcategoryId null when filed only at the top level
 * @param coverImage null until one is set; carries the dimensions §5.3's minimum
 *     is measured against
 * @param goalAmount null on a draft
 * @param currency what the goal and every reward price is denominated in. Here so
 *     that a refusal can quote the bound in the campaign's own money rather than
 *     as a bare number
 * @param durationDays null on a draft
 * @param scheduledLaunchAt null unless the creator has chosen a launch moment
 * @param storyCharacters prose only, counted by {@link StoryDocuments#characterCount}
 *     — the same count the editor shows, or the two disagree about a campaign
 *     that is exactly at the minimum
 * @param storyMediaCount images and embeds in the story
 * @param risks the mandatory risks and challenges section, as stored
 * @param rewardPrices one entry per tier, so the count is its size. Two facts
 *     from one list rather than a count that could be out of step with it
 */
public record CampaignCompleteness(
        String title,
        String summary,
        UUID categoryId,
        UUID subcategoryId,
        CoverImage coverImage,
        BigDecimal goalAmount,
        String currency,
        Integer durationDays,
        Instant scheduledLaunchAt,
        int storyCharacters,
        int storyMediaCount,
        String risks,
        List<BigDecimal> rewardPrices) {

    public CampaignCompleteness {
        rewardPrices = List.copyOf(rewardPrices);
    }

    /** How many reward tiers the campaign offers. */
    public int rewardTierCount() {
        return rewardPrices.size();
    }
}
