package az.ideanest.project.domain;

import java.util.List;

/**
 * What the checklist found: every requirement, whether it is met, and one number
 * summarising the lot.
 *
 * <p><strong>One value, two audiences.</strong> The checklist endpoint renders it
 * whole and {@code ProjectTransitionService} asks it a single question —
 * {@link #isSubmittable()}. That is the arrangement the class comment on the
 * transition service asks for: the advice a creator reads and the rule that
 * refuses their submission are the same evaluation of the same rules, so the two
 * cannot drift into disagreeing about whether a campaign is ready.
 *
 * <h2>The score</h2>
 *
 * <p>A percentage over <em>all</em> requirements, blocking and advisory, with a
 * blocking requirement weighing twice an advisory one.
 *
 * <p>Built only from blockers it would be a boolean wearing a percent sign: every
 * campaign that could be submitted would read 100, and the creator of a bare but
 * legal campaign would be told it was finished. Weighted equally with advisory
 * items it would say the opposite — that a campaign missing its cover image and
 * one missing a subcategory are the same distance from ready. Two to one is a
 * judgement, and it is the smallest weighting that keeps the two statements true:
 * a campaign cannot reach 100 while anything is undone, and it cannot reach 100
 * by doing only the optional half.
 *
 * <p>Rounded <strong>down</strong>. Reporting 100 for a campaign with an unmet
 * requirement is the one error this number must not make, and rounding to nearest
 * makes it at 24 requirements out of 25.
 *
 * @param items every requirement in {@link ChecklistRequirement}'s order, met and
 *     unmet alike. Unmet ones alone would be a list that empties as work is done
 *     and tells a creator nothing about how much there was
 */
public record ChecklistResult(List<ChecklistItem> items) {

    /** See the class comment: two to one, and why. */
    private static final int BLOCKING_WEIGHT = 2;

    private static final int ADVISORY_WEIGHT = 1;

    public ChecklistResult {
        items = List.copyOf(items);
    }

    /** The requirements §5.3 refuses a submission over, met and unmet. */
    public List<ChecklistItem> blocking() {
        return items.stream().filter(ChecklistItem::isBlocking).toList();
    }

    /** The ones that are advice. Never refuses anything; see {@link ChecklistSeverity}. */
    public List<ChecklistItem> advisory() {
        return items.stream().filter(item -> !item.isBlocking()).toList();
    }

    /**
     * The blocking requirements this campaign does not meet, in checklist order.
     *
     * <p>What {@code PROJECT_NOT_SUBMITTABLE} carries, so that a refusal names
     * every reason rather than the first one — a creator told about one missing
     * field at a time submits four times to learn about four.
     */
    public List<ChecklistItem> unmetBlocking() {
        return items.stream().filter(ChecklistItem::refusesSubmission).toList();
    }

    /** Whether §5.3 permits this campaign to be submitted. */
    public boolean isSubmittable() {
        return unmetBlocking().isEmpty();
    }

    /** 0–100. See the class comment for how it is weighted and why. */
    public int score() {
        int earned = 0;
        int available = 0;
        for (ChecklistItem item : items) {
            int weight = item.isBlocking() ? BLOCKING_WEIGHT : ADVISORY_WEIGHT;
            available += weight;
            if (item.satisfied()) {
                earned += weight;
            }
        }
        // Unreachable — the checklist always has requirements — but a division by
        // zero here would be a 500 on the editor's most-read screen.
        return available == 0 ? 0 : Math.floorDiv(earned * 100, available);
    }
}
