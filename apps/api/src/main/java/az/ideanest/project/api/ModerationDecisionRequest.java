package az.ideanest.project.api;

import jakarta.validation.constraints.Size;

/**
 * A moderator's note on a decision.
 *
 * <p>One body for all three outcomes, because it is one field. Whether it is
 * <em>required</em> differs — a rejection and a request for changes must say why,
 * an approval need not — and that difference is enforced in
 * {@code ProjectTransitionService} rather than by an annotation here. The
 * requirement belongs to the decision, and an annotation would have to be
 * duplicated onto two request types that were otherwise identical.
 *
 * @param note written for the creator, who is shown it and has to act on it
 */
public record ModerationDecisionRequest(
        @Size(max = 2000, message = "A note may not exceed 2000 characters") String note) {
}
