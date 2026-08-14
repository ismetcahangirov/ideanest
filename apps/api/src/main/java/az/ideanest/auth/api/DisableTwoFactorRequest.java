package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password the current password
 * @param code a current six-digit code, or null when a recovery code is used
 * @param recoveryCode a recovery code, for somebody switching two-factor off
 *     because the phone with the authenticator on it is gone
 */
public record DisableTwoFactorRequest(
        @NotBlank(message = "Your current password is required") @Size(max = 256) String password,
        @Size(max = 16) String code,
        @Size(max = 40) String recoveryCode) {
}
