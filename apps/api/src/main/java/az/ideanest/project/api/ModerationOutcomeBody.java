package az.ideanest.project.api;

import az.ideanest.project.application.ModerationOutcome;
import java.time.Instant;

/**
 * What platform staff last decided about a campaign, as its creator reads it.
 *
 * <p>The moderator's identity is deliberately absent. The creator needs the
 * decision and the reason; who took it is in
 * {@code project_state_transitions} for whoever has to review the decision later,
 * and naming an individual member of staff on a screen shown to somebody whose
 * campaign was just refused invites a conversation that should go to support.
 *
 * @param outcome {@code APPROVED}, {@code CHANGES_REQUESTED}, or {@code REJECTED}
 * @param note what the moderator wrote. Required for anything the creator has to
 *     act on; absent only for an approval nobody annotated
 * @param current whether the campaign is still in the state this decision produced
 */
public record ModerationOutcomeBody(String outcome, String note, Instant decidedAt, boolean current) {

    static ModerationOutcomeBody of(ModerationOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        return new ModerationOutcomeBody(
                outcome.outcome().name(), outcome.note(), outcome.decidedAt(), outcome.current());
    }
}
