package az.ideanest.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * §4.1's A-12, the ask.
 *
 * @param currentPassword required for the same reason A-13 requires it, and with
 *     one more behind it: the address on an account is what a password reset is
 *     sent to, so moving it is the last step of taking the account over. A stolen
 *     access token must not be enough
 * @param newEmail the address to move to. Normalised by {@code EmailAddress} once
 *     it reaches the service, so case and surrounding space are not a second
 *     address
 */
public record ChangeEmailRequest(
        @NotBlank(message = "Your current password is required") @Size(max = 256) String currentPassword,
        @NotBlank(message = "A new email address is required")
                @Email(message = "That is not an email address")
                @Size(max = 254)
                String newEmail) {
}
