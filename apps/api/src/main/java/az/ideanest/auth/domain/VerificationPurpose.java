package az.ideanest.auth.domain;

/**
 * What a verification token is for.
 *
 * <p>Recorded on the token and checked when it is redeemed. Without it, a token
 * issued to prove an address works would also reset the password on that
 * account — and email is the channel an attacker with mailbox access already
 * controls.
 */
public enum VerificationPurpose {

    /** Proves the address exists and belongs to whoever registered with it. */
    EMAIL_VERIFICATION,

    /** Authorises setting a new password without knowing the old one. */
    PASSWORD_RESET
}
