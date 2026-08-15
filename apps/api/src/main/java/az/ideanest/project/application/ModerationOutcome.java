package az.ideanest.project.application;

import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.domain.ProjectStateTransition;
import java.time.Instant;

/**
 * The last decision platform staff took on a campaign, as its creator needs to
 * read it.
 *
 * <p><strong>This is the half of {@code requestChanges} that was missing.</strong>
 * §6.1 has {@code SUBMITTED → CHANGES_REQUESTED → SUBMITTED} so that a moderator
 * can send a fixable campaign back instead of rejecting it, and the note — what to
 * change — is written on the {@code project_state_transitions} row. Until now
 * nothing read it back. A creator whose campaign came back saying "changes
 * requested" and nothing else has been given a state and no instruction, which is
 * the exact failure the note exists to prevent.
 *
 * <p>Assembled from the newest transition row whose actor was a
 * {@link ActorRole#MODERATOR}. That is deliberately "the last moderation
 * decision", not "the decision that produced the current state": after a
 * resubmission the campaign is in {@code SUBMITTED} and the last thing anybody
 * said about it is still the note it was sent back with, which is what the creator
 * is working from.
 *
 * @param outcome the state the decision moved the campaign to —
 *     {@code APPROVED}, {@code CHANGES_REQUESTED}, or {@code REJECTED}
 * @param note what the moderator wrote. Required for a rejection and for a change
 *     request, optional commentary on an approval, so this is null only for an
 *     approval nobody annotated
 * @param decidedAt when
 * @param current whether the campaign is still in the state this decision
 *     produced. <strong>Computed here rather than left to the client</strong>:
 *     the difference between "you must act on this" and "this is what happened
 *     last time" is the difference between a banner and a footnote, and a client
 *     comparing two enums to work it out is a client that will eventually compare
 *     them wrongly and shout at somebody whose campaign is fine
 */
public record ModerationOutcome(ProjectState outcome, String note, Instant decidedAt, boolean current) {

    /** The decision as it stands against the campaign's present state. */
    static ModerationOutcome of(ProjectStateTransition decision, ProjectState state) {
        return new ModerationOutcome(
                decision.getToState(), decision.getNote(), decision.getCreatedAt(), decision.getToState() == state);
    }
}
