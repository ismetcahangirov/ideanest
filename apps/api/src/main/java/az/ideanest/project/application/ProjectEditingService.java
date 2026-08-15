package az.ideanest.project.application;

import az.ideanest.project.domain.CoverImage;
import az.ideanest.project.domain.Project;
import az.ideanest.project.infrastructure.CategoryRepository;
import az.ideanest.project.infrastructure.ProjectRepository;
import az.ideanest.project.infrastructure.SubcategoryRepository;
import az.ideanest.shared.Money;
import az.ideanest.shared.Slugs;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating a campaign and editing its basics.
 *
 * <p>Everything except the state, which is {@link ProjectTransitionService} and
 * only that. The split is the point: a service that could both edit a field and
 * move a state would eventually do both in one method, and the audit row would
 * become a thing that describes some of the change.
 *
 * <p><strong>Validation is here as well as in the database.</strong> Every rule
 * below is also a check constraint on {@code projects}. The constraint is what
 * holds against a support query, a bulk import, and a bug; this is what turns the
 * same rule into a 400 naming the field, because a constraint violation reaches
 * the client as a 500 and tells them nothing they can act on.
 *
 * <p><strong>What is not here.</strong> Which fields are editable in which state
 * is #36 — §5.3 freezes the goal and the deadline after launch, and the response's
 * {@code lockedFields} is where that arrives. This service enforces one narrow
 * part of it, the part the database also refuses: a launched campaign cannot have
 * its goal or duration removed, because §5.1 could then not resolve it. Everything
 * else about locking is deliberately absent rather than half-implemented in two
 * places.
 */
@Service
public class ProjectEditingService {

    /** §5.3. Also {@code projects_title_length}. */
    private static final int TITLE_MAX = 60;

    /** §5.3. Also {@code projects_blurb_length}. */
    private static final int BLURB_MAX = 135;

    /** §5.3: 1-60 days, 30 recommended. Also {@code projects_duration_in_range}. */
    private static final int DURATION_MIN = 1;

    private static final int DURATION_MAX = 60;

    /**
     * How many numbered slugs to try before falling back to a random suffix. The
     * same reasoning as {@code UserAccounts}: a creator with ten drafts called
     * "Untitled" gets readable URLs, and the eleventh gets an unreadable one
     * rather than a query loop that grows with their indecision.
     */
    private static final int SLUG_ATTEMPTS = 10;

    /** Leaves room for a suffix inside the eighty-character column limit. */
    private static final int SLUG_BASE_LIMIT = 60;

    /** When a title folds to nothing this can transliterate. */
    private static final String SLUG_FALLBACK = "project";

    /**
     * The only currency the platform settles in today. A creator-selectable
     * currency needs a rate source, a payout account per currency, and a decision
     * about which one the fee is taken in; none of that exists, so the goal's
     * currency is accepted from the client and refused if it is anything else,
     * rather than being silently ignored.
     */
    private static final String SUPPORTED_CURRENCY = "AZN";

    private final ProjectRepository projects;
    private final CategoryRepository categories;
    private final SubcategoryRepository subcategories;
    private final ProjectAccess access;
    private final ProjectTransitionService transitions;

    public ProjectEditingService(
            ProjectRepository projects,
            CategoryRepository categories,
            SubcategoryRepository subcategories,
            ProjectAccess access,
            ProjectTransitionService transitions) {
        this.projects = projects;
        this.categories = categories;
        this.subcategories = subcategories;
        this.access = access;
        this.transitions = transitions;
    }

    /**
     * Opens a draft with a title and nothing else.
     *
     * <p>The editor is reached by creating the campaign, so this is the one write
     * that cannot demand a complete campaign. Everything §5.3 requires is filled in
     * afterwards, and the checklist (#37) is what refuses to submit an incomplete
     * one.
     *
     * <p>The creation audit row is written in this transaction, by the transition
     * service, so a campaign and the first line of its history exist together or
     * not at all.
     */
    @Transactional
    public Project create(UUID creatorId, String title) {
        String trimmed = requireTitle(title);
        Project project = Project.open(creatorId, trimmed, allocateSlug(creatorId, trimmed), SUPPORTED_CURRENCY);

        Project saved = projects.save(project);
        transitions.recordCreation(saved);
        return saved;
    }

    /** The campaign as its creator edits it. Refuses anybody else; see {@link ProjectAccess}. */
    @Transactional(readOnly = true)
    public Project forEdit(UUID projectId, UUID accountId) {
        return access.requireEditable(projectId, accountId);
    }

    /**
     * Applies a partial edit.
     *
     * <p>Field by field, and only the fields the client mentioned. The slug is
     * deliberately <em>not</em> recomputed when the title changes: it is in the
     * campaign's public URL, and a URL that changes when a typo is fixed breaks
     * every link anybody has already shared.
     */
    @Transactional
    public Project edit(UUID projectId, UUID accountId, ProjectPatch patch) {
        Project project = access.requireEditable(projectId, accountId);

        patch.title().ifPresent(title -> project.setTitle(requireTitle(title)));
        patch.blurb().ifPresent(blurb -> project.setBlurb(withinLength("blurb", blurb, BLURB_MAX)));
        patch.risks().ifPresent(risks -> project.setRisks(blankAsNull(risks)));
        patch.story().ifPresent(project::setStory);
        patch.scheduledLaunchAt().ifPresent(project::setScheduledLaunchAt);
        patch.coverImage().ifPresent(project::setCoverImage);
        patch.latePledgeEnabled()
                .ifPresent(enabled -> project.setLatePledgeEnabled(requireBoolean(enabled)));
        patch.durationDays().ifPresent(days -> project.setDurationDays(validDuration(project, days)));
        patch.goal().ifPresent(goal -> applyGoal(project, goal));

        if (patch.refilesTheProject()) {
            refile(project, patch);
        }

        return project;
    }

    /**
     * The goal, which is an amount and the currency it is in at once.
     *
     * <p>One field rather than two because they only mean anything together: a
     * currency changed without the amount reprices the campaign, and §10.3 sends
     * money as a pair for exactly that reason.
     */
    private void applyGoal(Project project, Money goal) {
        if (goal == null) {
            requireNotLaunched(project, "goal", "A launched campaign cannot have its goal removed.");
            project.setGoalAmount(null);
            return;
        }
        if (goal.amount().compareTo(BigDecimal.ZERO) <= 0) {
            // A goal of zero is met before it is announced. §5.3 leaves the real
            // minimum and maximum to configuration, and #37 checks the campaign
            // against them; this is the bound below which the number is not a goal
            // at all.
            throw new ProjectFieldRejectedException("goal", "A funding goal is more than zero.");
        }
        if (!SUPPORTED_CURRENCY.equals(goal.currency())) {
            throw new ProjectFieldRejectedException(
                    "goal", "Campaigns are funded in " + SUPPORTED_CURRENCY + " for now.");
        }
        project.setGoalAmount(goal.amount());
        project.setCurrency(goal.currency());
    }

    private Integer validDuration(Project project, Integer days) {
        if (days == null) {
            requireNotLaunched(project, "durationDays", "A launched campaign cannot have its duration removed.");
            return null;
        }
        if (days < DURATION_MIN || days > DURATION_MAX) {
            throw new ProjectFieldRejectedException(
                    "durationDays", "A campaign runs for between " + DURATION_MIN + " and " + DURATION_MAX + " days.");
        }
        return days;
    }

    /**
     * Files the campaign, keeping the category and the subcategory consistent.
     *
     * <p>A body that changes the category without mentioning the subcategory clears
     * the subcategory. The alternative — keeping it — would leave "Tabletop games"
     * under "Technology", which the database refuses outright; and silently keeping
     * a subcategory the creator did not re-choose is worse than clearing one they
     * can see is gone.
     */
    private void refile(Project project, ProjectPatch patch) {
        UUID categoryId =
                patch.categoryId().isPresent() ? patch.categoryId().value() : project.getCategoryId();

        UUID subcategoryId;
        if (patch.subcategoryId().isPresent()) {
            subcategoryId = patch.subcategoryId().value();
        } else if (patch.categoryId().isPresent()) {
            subcategoryId = null;
        } else {
            subcategoryId = project.getSubcategoryId();
        }

        if (categoryId == null && subcategoryId != null) {
            throw new ProjectFieldRejectedException(
                    "subcategoryId", "A subcategory needs the category it belongs to.");
        }
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new ProjectFieldRejectedException("categoryId", "That category does not exist.");
        }
        if (subcategoryId != null && !subcategories.existsByIdAndParentId(subcategoryId, categoryId)) {
            // Checked rather than left to the composite foreign key, which would
            // answer the same question with a 500.
            throw new ProjectFieldRejectedException(
                    "subcategoryId", "That subcategory is not part of the selected category.");
        }

        project.file(categoryId, subcategoryId);
    }

    /**
     * The narrow slice of #36 that the database also refuses.
     *
     * <p>§5.1 decides a campaign by comparing its total against its goal at its
     * deadline. A live campaign missing either cannot be decided, and
     * {@code projects_public_states_are_fully_specified} refuses the row — so
     * without this the client would get a 500 for a rule that has a perfectly good
     * 400. The full immutability rules, including the response's
     * {@code lockedFields}, are #36.
     */
    private static void requireNotLaunched(Project project, String field, String message) {
        if (project.getLaunchedAt() != null) {
            throw new ProjectFieldRejectedException(field, message);
        }
    }

    private static String requireTitle(String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            // Not clearable: the column is NOT NULL, and a campaign with no title
            // cannot be listed anywhere. A client sending null here is trying to
            // clear a required field, which is a different mistake from omitting it.
            throw new ProjectFieldRejectedException("title", "A campaign needs a title.");
        }
        return withinLength("title", trimmed, TITLE_MAX);
    }

    private static String withinLength(String field, String value, int max) {
        String normalised = blankAsNull(value);
        if (normalised != null && normalised.length() > max) {
            throw new ProjectFieldRejectedException(
                    field, "That is longer than " + max + " characters.");
        }
        return normalised;
    }

    /**
     * Treats an empty string as an absent value.
     *
     * <p>A creator who empties a textarea sends {@code ""}, not {@code null}. The
     * database refuses a blank summary — a zero-length string is not a summary —
     * and answering an autosave with a 400 for clearing a field is a worse
     * experience than storing what the creator plainly meant.
     */
    private static String blankAsNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean requireBoolean(Boolean value) {
        if (value == null) {
            throw new ProjectFieldRejectedException("latePledgeEnabled", "Late pledging is either on or off.");
        }
        return value;
    }

    /**
     * A readable slug that is free for this creator.
     *
     * <p>Scoped to the creator because the unique index is: two creators may both
     * launch a "coffee-table-book". Past the numbered attempts this stops asking and
     * appends randomness — the unique index is still the check that cannot lose a
     * race, so the pathological case is a retry rather than a duplicate.
     */
    private String allocateSlug(UUID creatorId, String title) {
        String base = Slugs.slugify(title);
        if (base.isEmpty()) {
            // A title written entirely in a script Slugs does not transliterate.
            base = SLUG_FALLBACK;
        }
        if (base.length() > SLUG_BASE_LIMIT) {
            base = trimTrailingHyphen(base.substring(0, SLUG_BASE_LIMIT));
        }

        if (!projects.existsByCreatorIdAndSlug(creatorId, base)) {
            return base;
        }
        for (int suffix = 2; suffix <= SLUG_ATTEMPTS; suffix++) {
            String candidate = base + "-" + suffix;
            if (!projects.existsByCreatorIdAndSlug(creatorId, candidate)) {
                return candidate;
            }
        }
        return base + "-" + Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()), 36);
    }

    private static String trimTrailingHyphen(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(0, end);
    }
}
