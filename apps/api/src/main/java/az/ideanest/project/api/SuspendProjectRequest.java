package az.ideanest.project.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why trust and safety is stopping a campaign — §4.11's AD-02.
 *
 * <p>Required, and not a formality. A suspension is terminal, it ends every pledge on
 * the campaign, and it is the platform taking somebody's funding down; the reason is
 * the only thing the creator, the backers, and whoever reviews the decision later are
 * ever told about why. It is recorded on the transition row, so it survives as part of
 * the campaign's history rather than as a message nobody kept.
 *
 * <p>The same shape as {@link CancelProjectRequest}, deliberately: the two halts are
 * the same request made by two different people, and a client that has to send a
 * differently named field depending on who is pressing the button is a client that
 * will eventually send the wrong one.
 */
public record SuspendProjectRequest(
        @NotBlank(message = "A reason is required")
                @Size(max = 2000, message = "A reason may not exceed 2000 characters")
                String reason) {
}
