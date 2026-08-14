package az.ideanest.auth.application;

/**
 * A two-factor change that was refused.
 *
 * <p>Separate from {@link AuthenticationFailedException} because the caller here
 * is already authenticated: they hold a valid access token and are being told
 * that this particular change will not be made, not that they are unknown. A
 * 401 would tell a client to sign in again, which would not help.
 *
 * <p>The message still does not say which part was wrong. Somebody using a
 * stolen access token to switch two-factor off should not be told whether it
 * was the password or the code they failed to produce.
 */
public class TwoFactorRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TwoFactorRejectedException(String message) {
        super(message);
    }
}
