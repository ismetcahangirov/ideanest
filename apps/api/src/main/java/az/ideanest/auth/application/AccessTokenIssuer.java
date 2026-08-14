package az.ideanest.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Minting the short-lived token a client sends on every request.
 *
 * <p>An interface so that the fact it is a JWT stays in one class. Everything
 * else in the module deals in "an access token and when it expires".
 */
public interface AccessTokenIssuer {

    /**
     * The one detail of the token's shape that is published.
     *
     * <p>Everything else about the encoding stays inside the implementation. The
     * rule that refuses a closing account is an authorisation rule and lives in
     * {@code SecurityConfiguration}, outside this module, so it has to name the
     * claim — and a claim name written down in two places is a claim name that
     * eventually differs in one of them, silently, in the direction of letting
     * requests through.
     */
    String DELETION_PENDING_CLAIM = "deletion_pending";

    /**
     * @param userId becomes {@code sub}
     * @param sessionId becomes {@code sid}. It is what makes an access token
     *     traceable to the sign-in that produced it, which matters when a
     *     session is revoked and somebody asks which requests it made
     * @param account what the token says about the account's standing
     * @param twoFactorAuthenticated whether the session behind this token proved
     *     a second factor. Becomes {@code amr}, which is what a payout action
     *     will read. Not part of {@link AccountStanding}: it is a fact about
     *     this sign-in, not about the account — switching two-factor on does not
     *     retroactively make yesterday's session a two-factor one
     */
    IssuedAccessToken issue(
            UUID userId, UUID sessionId, AccountStanding account, boolean twoFactorAuthenticated, Instant now);

    /**
     * The account facts an endpoint decides on, carried in the token so that no
     * endpoint needs a database read to find them out. Both are false-by-default
     * safe: a stale token grants less, never more.
     *
     * <p>A record rather than two booleans in a row, because two booleans in a
     * row are eventually passed in the wrong order and nothing says so.
     *
     * @param emailVerified whether the address has been proven
     * @param deletionPending whether the account is inside its deletion grace
     *     period, in which case it may do almost nothing —
     *     {@code SecurityConfiguration} is what enforces that
     */
    record AccountStanding(boolean emailVerified, boolean deletionPending) {
    }

    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
