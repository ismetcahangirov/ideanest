package az.ideanest.auth.application;

import java.time.Instant;

/**
 * What a correct password gets you: either a session, or a demand for the
 * second factor.
 *
 * <p>Sealed, and returned instead of tokens, so that a caller cannot forget the
 * second case. A method returning {@code IssuedTokens} and quietly returning
 * null — or throwing a "two-factor required" exception that some handler maps
 * to a 200 — is how a second factor becomes optional in practice while looking
 * mandatory in the code.
 */
public sealed interface SignInOutcome {

    /** No second factor is configured. The password was enough. */
    record Authenticated(IssuedTokens tokens) implements SignInOutcome {
    }

    /**
     * The password was right and there is no session yet.
     *
     * @param challenge the opaque value the second call must present. Handed
     *     back once, never stored in the clear
     * @param expiresAt when it stops working, which the client shows as a
     *     countdown rather than discovering by being refused
     */
    record TwoFactorRequired(String challenge, Instant expiresAt) implements SignInOutcome {
    }
}
