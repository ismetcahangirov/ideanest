package az.ideanest.user.api;

import az.ideanest.user.domain.ProfileVisibility;
import jakarta.validation.constraints.NotNull;

/**
 * §4.2's P-07, as a request body: "show my profile", or "do not".
 *
 * <p><strong>A named value rather than a boolean.</strong> {@code {"public": false}} would
 * be one character shorter and would put the meaning in the field name, where a third
 * state cannot go — and {@link ProfileVisibility} exists precisely because a third state
 * was considered and refused for reasons that could change. If it ever does, an enum gains
 * a value and every client that switches on it fails to compile; a boolean would gain a
 * second field, and the clients that never learned about it would keep sending the old one
 * and quietly mean something different.
 *
 * <p><strong>The enum is bound directly.</strong> A value that is neither {@code PUBLIC}
 * nor {@code PRIVATE} is refused by Jackson before the handler runs and comes back as
 * §10.4's 400 from {@code ApiExceptionHandler}, which is the same answer every other
 * malformed body on this service gets. Taking a string and validating it here would be a
 * second spelling of the vocabulary and a second place for it to drift from the check
 * constraint {@code users_profile_visibility_known} holds.
 *
 * @param visibility required. Absent is not "leave it as it is" — a PATCH with an empty
 *     body would then silently succeed at nothing, and a client whose serialiser dropped
 *     the field would believe it had turned the page off
 */
public record ProfileVisibilityRequest(@NotNull ProfileVisibility visibility) {
}
