package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * §4.1's A-13.
 *
 * @param currentPassword required even though the caller holds a valid access
 *     token, for the reason {@link EnableTwoFactorRequest} gives about enrolling
 *     a second factor: a token is fifteen minutes of somebody else's session if
 *     it leaks, and changing the password is how that becomes permanent
 * @param newPassword checked against {@code PasswordPolicy} before the current
 *     one is verified, so a password that will be refused is refused without
 *     spending an Argon2 verification on the way
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Your current password is required") @Size(max = 256) String currentPassword,
        @NotBlank(message = "A new password is required") @Size(max = 256) String newPassword) {
}
