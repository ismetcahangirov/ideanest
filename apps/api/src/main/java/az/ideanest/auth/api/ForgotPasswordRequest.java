package az.ideanest.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * §4.1's A-06, the ask.
 *
 * @param email the address to send a reset link to. Validated for shape only —
 *     whether it has an account here is deliberately not part of the answer, and
 *     {@code PasswordResetService} explains why an endpoint that said so would be
 *     an enumeration oracle
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "An email address is required")
                @Email(message = "That is not an email address")
                @Size(max = 254)
                String email) {
}
