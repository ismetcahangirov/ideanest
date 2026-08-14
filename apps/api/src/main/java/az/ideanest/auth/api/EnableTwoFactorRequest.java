package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password the current password. Required even though the caller holds a
 *     valid access token: a token is fifteen minutes of somebody else's session
 *     if it leaks, and enrolling a second factor onto an account you do not own
 *     is how you keep it
 */
public record EnableTwoFactorRequest(
        @NotBlank(message = "Your current password is required") @Size(max = 256) String password) {
}
