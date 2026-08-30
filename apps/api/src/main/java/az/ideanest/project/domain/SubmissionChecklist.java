package az.ideanest.project.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a campaign is complete enough to be submitted, requirement by
 * requirement.
 *
 * <p><strong>One class, two callers, and that is the entire design.</strong>
 * {@code GET /v1/projects/{id}/checklist} renders what this returns and
 * {@code ProjectTransitionService.submit} refuses on it. A second, "quick" copy
 * of the rules on the submission path is exactly the bug this arrangement
 * prevents: the two would agree on the day they were written and disagree the
 * first time §5.3 moved, and the symptom would be a creator staring at a green
 * checklist beside a button that answers 409.
 *
 * <p><strong>A table of rules, not a chain of conditionals</strong>, for the
 * reason {@link ProjectStateMachine} gives. Every requirement is one line in
 * {@link #evaluate}, in the order {@link ChecklistRequirement} declares, so the
 * whole of §5.3 can be read at once and asserted against the specification in a
 * plain unit test.
 *
 * <p><strong>No Spring and no database.</strong> Nothing here is injected or
 * loaded; the facts arrive as a {@link CampaignCompleteness} and the configurable
 * bounds as {@link SubmissionLimits}. The rule that decides whether a campaign
 * may be put in front of moderation should not need PostgreSQL to be checked, and
 * the reward facts it needs come from another module — see
 * {@link CampaignCompleteness} for why they arrive as numbers.
 *
 * <h2>What §5.3 asks for and this cannot check</h2>
 *
 * <ul>
 *   <li><strong>That a cover supplied as a typed URL is really 1024×576.</strong> For
 *       an uploaded one this is no longer true: ingestion measures the file and writes
 *       what it measured, so {@code CampaignCompleteness} carries a fact rather than a
 *       claim. A cover that is still a URL a creator typed is unchanged — the dimensions
 *       come from their browser and a client could send any pair of numbers.
 *       <p>Which is one of the two reasons the size rule stopped refusing submissions;
 *       see {@link ChecklistRequirement#COVER_IMAGE_SIZE} for the other.
 *   <li><strong>§5.4's prohibited content and §5.5's obligations.</strong> Neither
 *       is a property of a row. They are what moderation is for, which is why
 *       submission leads to a queue rather than straight to {@code APPROVED}.
 * </ul>
 *
 * <p>The immutability half of §5.3 — a frozen goal and deadline after launch — is
 * not here either. It is not a completeness rule: it constrains editing, it
 * belongs to #36, and a campaign is submitted long before any of it applies.
 */
public final class SubmissionChecklist {

    /** §5.3. Also {@code projects_title_length} and {@code ProjectEditingService}. */
    public static final int TITLE_MAX_CHARACTERS = 60;

    /** §5.3. Also {@code projects_blurb_length}. */
    public static final int SUMMARY_MAX_CHARACTERS = 135;

    /**
     * §5.3: at least 1024×576, and the same pair as {@code coverImage.ts}.
     *
     * <p><strong>Advice, not a gate.</strong> {@link ChecklistRequirement#COVER_IMAGE_SIZE}
     * carries the argument. The floor that does refuse an image is
     * {@code MediaAsset.MINIMUM_EDGE}, and it is a much lower number, because it answers a
     * different question — "can this be displayed at all" rather than "does this look good
     * across a hero".
     */
    public static final int COVER_MIN_WIDTH = 1024;

    public static final int COVER_MIN_HEIGHT = 576;

    /** §5.3: 1–60 days, 30 recommended. Also {@code projects_duration_in_range}. */
    public static final int DURATION_MIN_DAYS = 1;

    public static final int DURATION_MAX_DAYS = 60;

    /** §5.3, counted as {@link StoryDocuments#characterCount} counts it. */
    public static final int STORY_MIN_CHARACTERS = 500;

    /** §5.3: <strong>required</strong>, minimum 200. */
    public static final int RISKS_MIN_CHARACTERS = 200;

    /** §5.3: 0–100 tiers. Also {@code RewardService}, which is what stops a 101st being created. */
    public static final int REWARD_TIER_MAX = 100;

    private SubmissionChecklist() {
    }

    /**
     * §5.3 and §4.6, applied to one campaign.
     *
     * <p>Every requirement is evaluated, including the ones that pass. A method
     * that stopped at the first failure would be cheaper and would produce a
     * checklist that grows one row at a time as a creator fixes things, which is
     * the interface equivalent of a compiler that reports one error per run.
     */
    public static ChecklistResult evaluate(CampaignCompleteness campaign, SubmissionLimits limits) {
        List<ChecklistItem> items = new ArrayList<>();

        // --- What the campaign is -------------------------------------------
        items.add(text(
                ChecklistRequirement.TITLE,
                campaign.title(),
                TITLE_MAX_CHARACTERS,
                "A campaign needs a title.",
                "title"));
        items.add(text(
                ChecklistRequirement.SUMMARY,
                campaign.summary(),
                SUMMARY_MAX_CHARACTERS,
                "A one-line summary is what appears under the campaign everywhere it is listed.",
                "summary"));

        items.add(ChecklistItem.of(
                ChecklistRequirement.CATEGORY,
                campaign.categoryId() != null,
                "Choose the category this campaign belongs to. It is how backers find it."));
        items.add(ChecklistItem.of(
                ChecklistRequirement.SUBCATEGORY,
                campaign.subcategoryId() != null,
                "Filed only at the top level. A subcategory puts the campaign in front of the "
                        + "people browsing for exactly this."));

        items.add(coverPresent(campaign.coverImage()));
        items.add(coverSize(campaign.coverImage()));

        // --- What it costs and how long ----------------------------------
        items.add(goal(campaign, limits));
        items.add(duration(campaign.durationDays()));
        items.add(ChecklistItem.of(
                ChecklistRequirement.SCHEDULED_LAUNCH,
                campaign.scheduledLaunchAt() != null,
                "No launch time is set, so the campaign opens whenever the button is pressed. "
                        + "Choosing the moment is worth more than most of this list."));

        // --- What it says ----------------------------------------------------
        items.add(ChecklistItem.of(
                ChecklistRequirement.STORY,
                campaign.storyCharacters() >= STORY_MIN_CHARACTERS,
                "The story is " + campaign.storyCharacters() + " characters. At least "
                        + STORY_MIN_CHARACTERS + " are needed."));
        items.add(ChecklistItem.of(
                ChecklistRequirement.STORY_MEDIA,
                campaign.storyMediaCount() > 0,
                "The story is all text. One picture or video of the thing being made does more "
                        + "than another paragraph about it."));
        items.add(risks(campaign.risks()));

        // --- What it offers --------------------------------------------------
        items.add(ChecklistItem.of(
                ChecklistRequirement.REWARDS_OFFERED,
                campaign.rewardTierCount() > 0,
                "There is nothing for a backer to choose. A campaign with no rewards is asking "
                        + "for a donation."));
        items.add(ChecklistItem.of(
                ChecklistRequirement.REWARD_TIER_COUNT,
                campaign.rewardTierCount() <= REWARD_TIER_MAX,
                "There are " + campaign.rewardTierCount() + " rewards. A campaign offers at most "
                        + REWARD_TIER_MAX + "."));
        items.add(rewardPrices(campaign, limits));

        return new ChecklistResult(items);
    }

    // ------------------------------------------------------------------
    // The rules that need more than a comparison
    // ------------------------------------------------------------------

    /**
     * Present, and within its length.
     *
     * <p>Both halves are one requirement rather than two, because a creator does
     * not have a title-length problem separately from a title problem — and a
     * checklist row that is green for "has a title" beside a red one for "title
     * length" is two rows about one field.
     *
     * <p>Counted in code points, which is what {@code varchar(n)} counts and what
     * the editor's own counter counts. Counting UTF-16 units here would refuse a
     * sixty-character title containing an emoji that the database was happy with.
     */
    private static ChecklistItem text(
            ChecklistRequirement requirement, String value, int max, String whenMissing, String noun) {

        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return ChecklistItem.unmet(requirement, whenMissing);
        }
        int characters = trimmed.codePointCount(0, trimmed.length());
        return ChecklistItem.of(
                requirement,
                characters <= max,
                "The " + noun + " is " + characters + " characters. The limit is " + max + ".");
    }

    /**
     * There is one, and that half still refuses a submission.
     *
     * <p>Split from the size rule below, which is the opposite of what {@link #text} does for
     * a title — and the reason is that the two really are different requirements here. A
     * campaign with no cover has nothing to show in any list on the platform; a campaign with
     * a small one has something to show that could be better. Those are not the same
     * sentence, and until this split they were the same row.
     */
    private static ChecklistItem coverPresent(CoverImage cover) {
        return ChecklistItem.of(
                ChecklistRequirement.COVER_IMAGE,
                cover != null,
                "A cover image is required. It is the campaign everywhere it is listed.");
    }

    /**
     * It is large enough to look right across a hero, and this is advice.
     *
     * <p>See {@link ChecklistRequirement#COVER_IMAGE_SIZE} for why it stopped blocking. The
     * short version: the number used to come from the creator's browser, so the rule caught
     * the honest and missed everybody else, and it stopped people at the first screen of the
     * editor on a platform that had nowhere to upload a larger file.
     *
     * <p>Met when there is no cover at all, deliberately. The missing-cover case is
     * {@link #coverPresent}'s, and reporting both would put two red rows on the screen for
     * one thing to fix.
     */
    private static ChecklistItem coverSize(CoverImage cover) {
        if (cover == null) {
            return ChecklistItem.met(ChecklistRequirement.COVER_IMAGE_SIZE);
        }
        return ChecklistItem.of(
                ChecklistRequirement.COVER_IMAGE_SIZE,
                cover.width() >= COVER_MIN_WIDTH && cover.height() >= COVER_MIN_HEIGHT,
                "The cover image is " + cover.width() + "×" + cover.height() + ". At least "
                        + COVER_MIN_WIDTH + "×" + COVER_MIN_HEIGHT + " reads better across the "
                        + "full-width header on a campaign page.");
    }

    /**
     * Present, above zero, and inside the configured bounds.
     *
     * <p>The amount is quoted back in the campaign's own currency. "A goal is at
     * least 100" is a number a creator has to guess the units of, and guessing
     * wrong by a factor of a hundred is a plausible mistake on a funding platform.
     */
    private static ChecklistItem goal(CampaignCompleteness campaign, SubmissionLimits limits) {
        BigDecimal goal = campaign.goalAmount();
        if (goal == null || goal.signum() <= 0) {
            return ChecklistItem.unmet(
                    ChecklistRequirement.GOAL, "Set how much this campaign needs to raise.");
        }
        if (goal.compareTo(limits.goalMinimum()) < 0) {
            return ChecklistItem.unmet(
                    ChecklistRequirement.GOAL,
                    "The goal is " + money(goal, campaign) + ". The smallest goal a campaign can "
                            + "run with is " + money(limits.goalMinimum(), campaign) + ".");
        }
        return ChecklistItem.of(
                ChecklistRequirement.GOAL,
                goal.compareTo(limits.goalMaximum()) <= 0,
                "The goal is " + money(goal, campaign) + ". The largest goal a campaign can run "
                        + "with is " + money(limits.goalMaximum(), campaign) + ".");
    }

    private static ChecklistItem duration(Integer days) {
        if (days == null) {
            return ChecklistItem.unmet(
                    ChecklistRequirement.DURATION,
                    "Set how many days the campaign runs for. " + DURATION_MAX_DAYS
                            + " is the maximum; 30 is the usual choice.");
        }
        return ChecklistItem.of(
                ChecklistRequirement.DURATION,
                days >= DURATION_MIN_DAYS && days <= DURATION_MAX_DAYS,
                "The campaign runs for " + days + " days. A campaign runs for between "
                        + DURATION_MIN_DAYS + " and " + DURATION_MAX_DAYS + ".");
    }

    /**
     * §5.3's one emphatic requirement.
     *
     * <p>Required and at least two hundred characters, and the two are reported
     * differently: a creator who has written nothing is being told about a section
     * they may not have noticed, and one who has written eighty characters is
     * being told how much further to go.
     */
    private static ChecklistItem risks(String risks) {
        String trimmed = risks == null ? "" : risks.trim();
        if (trimmed.isEmpty()) {
            return ChecklistItem.unmet(
                    ChecklistRequirement.RISKS,
                    "Required. Say what could go wrong with making and delivering this, and what "
                            + "you would do about it.");
        }
        int characters = trimmed.codePointCount(0, trimmed.length());
        return ChecklistItem.of(
                ChecklistRequirement.RISKS,
                characters >= RISKS_MIN_CHARACTERS,
                "The risks section is " + characters + " characters. At least " + RISKS_MIN_CHARACTERS
                        + " are needed.");
    }

    /**
     * Every tier at or above the smallest chargeable amount.
     *
     * <p>Counted rather than named. The refusal sends the creator to the rewards
     * tab, where the prices are in front of them; a list of tier titles in a
     * checklist row would be a second reward list to keep in step with the first.
     *
     * <p>A campaign with no tiers passes, and that is not an oversight: §5.3
     * permits zero, and "every tier is priced correctly" is true of no tiers. The
     * advisory {@code REWARDS_OFFERED} is where the absence is reported.
     */
    private static ChecklistItem rewardPrices(CampaignCompleteness campaign, SubmissionLimits limits) {
        BigDecimal floor = limits.rewardPriceMinimum();
        long underpriced = campaign.rewardPrices().stream()
                .filter(price -> price == null || price.compareTo(floor) < 0)
                .count();

        return ChecklistItem.of(
                ChecklistRequirement.REWARD_PRICES,
                underpriced == 0,
                underpriced + (underpriced == 1 ? " reward is" : " rewards are") + " priced below "
                        + money(floor, campaign) + ", which is the smallest amount that can be charged.");
    }

    /**
     * An amount with the currency it is in.
     *
     * <p>Prose rather than a {@code Money} — this is a sentence in a checklist row,
     * not a field a client parses. The wire format §10.3 requires is used where
     * money is a value, which is everywhere except here.
     */
    private static String money(BigDecimal amount, CampaignCompleteness campaign) {
        String currency = campaign.currency() == null ? "" : " " + campaign.currency();
        return amount.stripTrailingZeros().toPlainString() + currency;
    }
}
