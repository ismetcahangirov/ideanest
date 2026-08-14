package az.ideanest.auth.application;

import az.ideanest.shared.EmailAddress;

/**
 * Sending what registration produces.
 *
 * <p>A port, because transactional email — templates, retries, bounces,
 * suppression lists — is #86 and does not belong inside registration. What
 * belongs here is the decision about <em>what</em> is sent and when, which is
 * what this interface names.
 */
public interface VerificationNotifier {

    /** The link that proves the address exists. */
    void sendEmailVerification(EmailAddress email, String token, String locale);

    /**
     * Sent when somebody tries to register with an address that already has an
     * account.
     *
     * <p>The registration response is identical either way, so this message is
     * the only signal — and it goes to the person who owns the address rather
     * than to the person who typed it. That is the point: it tells the owner
     * that someone is probing their account, and tells the prober nothing.
     */
    void sendRegistrationAttemptOnExistingAccount(EmailAddress email, String locale);
}
