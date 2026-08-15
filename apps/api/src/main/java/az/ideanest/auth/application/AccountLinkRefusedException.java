package az.ideanest.auth.application;

/**
 * A provider account that proved an address which already belongs to an account
 * here, in a state where linking the two would not be safe.
 *
 * <p>Distinct from {@link AuthenticationFailedException} on purpose, and it is
 * the one place in auth where a refusal says more than "those credentials are
 * not valid". The caller has just proved to Google or Apple that they control
 * this address, and telling the person who owns an address that it is in use
 * here tells them something they could learn by asking for a password reset. The
 * enumeration argument does not apply to somebody who has proven ownership.
 *
 * <p>It does not apply to the caller who has <em>not</em> proven it, which is
 * why an unverified provider address never reaches this exception — it is
 * refused as an ordinary authentication failure, with the ordinary message.
 */
public class AccountLinkRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountLinkRefusedException(String message) {
        super(message);
    }
}
