package az.ideanest.moderation.api;

import az.ideanest.moderation.domain.ContentReport;
import jakarta.validation.constraints.Size;

/**
 * A moderator's note on a decision.
 *
 * <p>One body for both outcomes, because it is one field — the same arrangement
 * {@code ModerationDecisionRequest} makes for the three campaign outcomes.
 *
 * <p><strong>Optional for both, unlike a campaign rejection.</strong> There the note
 * is required because the creator is shown it and has to act on it; nothing in this
 * release shows a resolution note to the person who made the report. A required field
 * that nobody reads is a field people type "ok" into, which is worse than an empty
 * one: it looks like a reason.
 *
 * @param note written for the next moderator to see the reasoning of the last one
 */
public record ReportResolutionRequest(
        @Size(
                        max = ContentReport.RESOLUTION_NOTE_MAX_LENGTH,
                        message = "A note may not exceed 2000 characters")
                String note) {
}
