package az.ideanest.project.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a creator is stopping a live campaign.
 *
 * <p>Required, and not a formality: cancelling abandons commitments people made
 * with their card details on file, and the reason is what they are shown. It is
 * recorded on the transition row, so it survives as part of the campaign's history
 * rather than as a notification nobody kept.
 *
 * @param reason shown to backers. Bounded because it is stored and displayed, not
 *     because anybody needs more than a paragraph to explain a cancellation
 */
public record CancelProjectRequest(
        @NotBlank(message = "A reason is required")
                @Size(max = 2000, message = "A reason may not exceed 2000 characters")
                String reason) {
}
