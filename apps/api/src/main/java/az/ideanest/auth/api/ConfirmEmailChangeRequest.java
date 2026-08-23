package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * §4.1's A-12, the confirmation.
 *
 * <p>The token in the body rather than the path, for the reason
 * {@link ResetPasswordRequest} states. No password and no session: the credential
 * is the link, and requiring a session as well would mean the link only works in
 * the browser that asked for it — which is the browser least likely to be signed
 * in to the new mailbox.
 *
 * @param token the value from the link sent to the new address
 */
public record ConfirmEmailChangeRequest(
        @NotBlank(message = "The link is required") @Size(max = 512) String token) {
}
