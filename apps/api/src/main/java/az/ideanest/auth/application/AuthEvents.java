package az.ideanest.auth.application;

import az.ideanest.shared.EmailAddress;

/**
 * What registration announces once its transaction has committed.
 *
 * <p>Email is sent from a listener rather than inline, because a message sent
 * inside a transaction that then rolls back cannot be unsent: the user receives
 * a verification link for an account that does not exist.
 *
 * <p>This is not yet the transactional outbox (#135). A crash between the
 * commit and the send loses the message, and the user has to ask for another
 * one. The outbox closes that window by writing the intent to send in the same
 * transaction as the account.
 */
public final class AuthEvents {

    private AuthEvents() {
    }

    /** An account was created and needs its address proven. */
    public record EmailVerificationRequested(EmailAddress email, String token, String locale) {
    }

    /** Somebody tried to register an address that is already spoken for. */
    public record RegistrationAttemptedOnExistingAccount(EmailAddress email, String locale) {
    }
}
