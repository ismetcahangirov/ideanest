package az.ideanest.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email the account's address
 * @param password the raw password
 * @param deviceLabel what to call this device in the user's session list. The
 *     client supplies it because only the client knows; it is never trusted for
 *     anything but display
 * @param tokenDelivery {@code cookie} or {@code body}. Defaults to
 *     {@code cookie}: a browser must not be handed a refresh token it can read,
 *     because anything that can read it — including a script injected by one
 *     cross-site scripting bug — has a thirty-day credential. A native client
 *     has no cookie jar worth using and its own secure storage, so it asks for
 *     the body
 */
public record SignInRequest(
        @NotBlank(message = "An email address is required")
                @Email(message = "That is not an email address")
                @Size(max = 254)
                String email,
        @NotBlank(message = "A password is required") @Size(max = 256) String password,
        @Size(max = 120, message = "That device name is too long") String deviceLabel,
        String tokenDelivery) {

    public boolean wantsTokenInBody() {
        return "body".equalsIgnoreCase(tokenDelivery);
    }
}
