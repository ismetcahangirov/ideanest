package az.ideanest.user.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Confirming a deletion.
 *
 * <p>Only the password. There is nothing else to say: the account is the one
 * the token was issued for, and letting the body name an account would make the
 * endpoint a way to close somebody else's.
 *
 * @param password the account's own password. Not length-checked here — the
 *     policy applies to passwords being set, and rejecting a short one on the
 *     way in would tell the caller their guess was the wrong shape rather than
 *     simply wrong
 */
public record DeleteAccountRequest(@NotBlank(message = "A password is required.") String password) {
}
