package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param code the six digits the authenticator is showing. Bounded generously
 *     rather than exactly, because the app displays {@code 123 456} and people
 *     type what they see; what a code has to be is decided where it is checked
 */
public record ConfirmTwoFactorRequest(
        @NotBlank(message = "A code is required") @Size(max = 16) String code) {
}
