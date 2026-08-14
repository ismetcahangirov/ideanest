package az.ideanest.auth.application;

/**
 * Credentials that were not accepted.
 *
 * <p>One exception and one message for every cause: no such account, wrong
 * password, refresh token unknown, refresh token spent, session revoked. The
 * caller is told "these credentials are not valid" and nothing else.
 *
 * <p>Distinguishing them would be friendlier and would also answer "does this
 * address have an account here" for anybody who asks — which is the same
 * enumeration oracle registration goes out of its way to avoid, reachable
 * through a different door.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
