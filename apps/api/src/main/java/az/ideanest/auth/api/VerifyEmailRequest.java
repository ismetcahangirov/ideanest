package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param token the value from the verification link. Sent in a body rather than
 *     in the query string, because a query string is written to access logs,
 *     kept in browser history, and forwarded in the {@code Referer} header of
 *     whatever the page loads next — and this value is a credential until it is
 *     spent.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "A token is required")
                @Size(max = 256, message = "That is not a verification token")
                String token) {
}
