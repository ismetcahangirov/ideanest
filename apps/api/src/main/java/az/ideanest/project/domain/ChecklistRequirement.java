package az.ideanest.project.domain;

/**
 * One thing the completeness checklist asks about a campaign.
 *
 * <p><strong>The names are the wire format</strong>, exactly as
 * {@link Capability}'s are. A client branches on them to highlight a control, and
 * the {@code PROJECT_NOT_SUBMITTABLE} problem detail names the ones that refused
 * a submission, so renaming one is a breaking change to every client that acted
 * on it.
 *
 * <p><strong>Each value carries its severity and its section, and that is
 * deliberate.</strong> Both are properties of the requirement rather than of a
 * particular campaign — "a cover image is required" and "a cover image is fixed
 * in the basics tab" are true of every campaign there has ever been. Deciding
 * them per evaluation would put the same two facts in as many places as there are
 * branches in {@link SubmissionChecklist}, and the first branch to disagree would
 * send a creator to the wrong tab for a rule that was not really blocking.
 *
 * <p>The order below is the order a creator sees, and it is the order a campaign
 * is built in: what it is, what it costs, what it says, what it offers. A
 * checklist sorted by severity would put the cover image above the title.
 */
public enum ChecklistRequirement {

    /** §5.3: 1–60 characters. Also {@code projects_title_length}. */
    TITLE("Title", ChecklistSeverity.BLOCKING, EditorSection.BASICS),

    /** §5.3: 1–135 characters. {@code blurb} on the wire and in the schema. */
    SUMMARY("Short summary", ChecklistSeverity.BLOCKING, EditorSection.BASICS),

    /** §4.6: a campaign is filed before it can be discovered. */
    CATEGORY("Category", ChecklistSeverity.BLOCKING, EditorSection.BASICS),

    /**
     * A subcategory as well as a category.
     *
     * <p>Advisory: §5.3 does not require one, and plenty of campaigns genuinely
     * belong to none of a category's children. It is here because discovery
     * (§11.2) filters on it, and a campaign filed only at the top level competes
     * with every other campaign in the category rather than with the twelve it is
     * actually like.
     */
    SUBCATEGORY("Subcategory", ChecklistSeverity.ADVISORY, EditorSection.BASICS),

    /** §5.3: required. A campaign without one has nothing to show anywhere it is listed. */
    COVER_IMAGE("Cover image", ChecklistSeverity.BLOCKING, EditorSection.BASICS),

    /**
     * §5.3's 1024×576, and <strong>advisory since the media pipeline landed</strong>.
     *
     * <p>It used to be part of {@link #COVER_IMAGE} and it used to refuse a submission. Two
     * things were wrong with that, and they compounded.
     *
     * <p>It was <strong>unenforceable</strong>. Until ingestion existed nothing on the server
     * had seen the file: the dimensions were measured in the creator's browser and sent
     * alongside the URL, so the rule refused honest creators and was inert against anybody
     * who edited the number. {@code SubmissionChecklist} said so in its own header.
     *
     * <p>It was <strong>the first thing a creator hit</strong>. A phone photograph that is
     * 800×600 could not be recorded as a cover at all, and the only way past it was to go
     * and host a larger image somewhere else — on a platform that had no uploader.
     *
     * <p>Both are now different. The server measures the file, so the number is real, and
     * there is somewhere to upload one, so a creator is no longer sent away. What is left is
     * a judgement about how a small image looks stretched across a 1440px hero — which is
     * advice, and which moderation reviews anyway. A hard floor still exists and is not this:
     * {@code MediaAsset.MINIMUM_EDGE} refuses anything too small to display at all.
     */
    COVER_IMAGE_SIZE("Cover image size", ChecklistSeverity.ADVISORY, EditorSection.BASICS),

    /** §5.3: present, above zero, and within the configured bounds. */
    GOAL("Funding goal", ChecklistSeverity.BLOCKING, EditorSection.BASICS),

    /** §5.3: 1–60 days, 30 recommended. */
    DURATION("Duration", ChecklistSeverity.BLOCKING, EditorSection.BASICS),

    /**
     * A launch date chosen in advance.
     *
     * <p>Advisory, and it is advice about marketing rather than about
     * completeness: §6.1 has {@code SCHEDULED} because the moment a campaign opens
     * decides how much of its own audience sees the first day, and a creator who
     * launches by pressing a button does it whenever they happen to be at a
     * keyboard.
     */
    SCHEDULED_LAUNCH("Scheduled launch", ChecklistSeverity.ADVISORY, EditorSection.BASICS),

    /** §5.3: at least 500 characters of prose, counted as {@link StoryDocuments} counts them. */
    STORY("Story", ChecklistSeverity.BLOCKING, EditorSection.STORY),

    /**
     * Something to look at inside the story.
     *
     * <p>Advisory. §4.6 gives the story inline media and third-party embeds, and a
     * story of six unbroken paragraphs is read by almost nobody — but a campaign
     * whose product is a novel may legitimately have nothing to show, so this
     * never refuses anything.
     */
    STORY_MEDIA("An image or video in the story", ChecklistSeverity.ADVISORY, EditorSection.STORY),

    /** §5.3: <strong>required</strong>, at least 200 characters. */
    RISKS("Risks and challenges", ChecklistSeverity.BLOCKING, EditorSection.STORY),

    /**
     * Something for a backer to choose.
     *
     * <p>Advisory, because §5.3 permits zero tiers and a donation-shaped campaign
     * is a real thing. It is on the list anyway: the reward list is what a backer
     * reads after the first paragraph, and a campaign offering nothing is asking
     * for a gift rather than a pledge.
     */
    REWARDS_OFFERED("At least one reward", ChecklistSeverity.ADVISORY, EditorSection.REWARDS),

    /** §5.3: 0–100 tiers. */
    REWARD_TIER_COUNT("Number of rewards", ChecklistSeverity.BLOCKING, EditorSection.REWARDS),

    /** §5.3: every tier priced at or above the smallest chargeable amount. */
    REWARD_PRICES("Reward prices", ChecklistSeverity.BLOCKING, EditorSection.REWARDS);

    private final String label;
    private final ChecklistSeverity severity;
    private final EditorSection section;

    ChecklistRequirement(String label, ChecklistSeverity severity, EditorSection section) {
        this.label = label;
        this.severity = severity;
        this.section = section;
    }

    /** What the requirement is, in a creator's words. Never says whether it is met. */
    public String label() {
        return label;
    }

    public ChecklistSeverity severity() {
        return severity;
    }

    public boolean isBlocking() {
        return severity == ChecklistSeverity.BLOCKING;
    }

    /** Where in the editor it is fixed. */
    public EditorSection section() {
        return section;
    }
}
