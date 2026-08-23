package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * §4.1's A-06, the redemption.
 *
 * <p><strong>The token is in the body and not in the path.</strong> The same
 * argument {@link VerifyEmailRequest} makes: a query string is written to access
 * logs, kept in browser history, and forwarded in the {@code Referer} header of
 * whatever the page loads next. This one is worse than a verification token,
 * because spending it sets a password.
 *
 * @param token the value from the link. 256 bits, URL-safe Base64
 * @param password the new password. Checked against {@code PasswordPolicy}
 *     before the link is spent, so a rejected password does not burn the link
 */
public record ResetPasswordRequest(
        @NotBlank(message = "The link is required") @Size(max = 512) String token,
        @NotBlank(message = "A new password is required") @Size(max = 256) String password) {
}
