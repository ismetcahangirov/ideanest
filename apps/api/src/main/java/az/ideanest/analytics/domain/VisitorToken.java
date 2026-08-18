package az.ideanest.analytics.domain;

import az.ideanest.shared.SecureTokens;

/**
 * What a visit is remembered by, before there is an account to remember it by.
 *
 * <p>A thin naming of {@link SecureTokens} rather than a second implementation of it:
 * 256 bits of {@code SecureRandom}, URL-safe, stored only as its SHA-256. The reason
 * it exists as a type at all is the sentence it lets this module state once —
 * <strong>a visitor identifier is randomness and is never derived from an
 * account</strong> — which is #94's hard requirement and the one that is easiest to
 * lose to a convenient shortcut.
 *
 * <h2>Why the shortcut is a user enumeration</h2>
 *
 * <p>The tempting versions are all cheap: a referral code that is the creator's
 * {@code users.id} in base64, a visitor identifier that is a hash of the account
 * identifier, a counter. Each of them turns "try a code" into "walk the platform's
 * users" — a counter directly, an encoding by decoding it, a hash of a 128-bit
 * identifier by hashing every identifier already known. A code minted here carries no
 * information about who holds it, so the only thing guessing it can produce is a
 * miss.
 *
 * <h2>Why only the hash is stored</h2>
 *
 * <p>{@code referral_touches.visitor_hash} is the SHA-256 and never the token. A
 * leaked copy of the table therefore does not let the reader forge a visit or claim
 * somebody else's touches, which matters more here than it looks: claiming a
 * visitor's touches is how anonymous browsing becomes attached to an account, so a
 * forgeable token would be a way to attach somebody else's browsing to your own
 * account.
 *
 * <p>Unsalted and with no work factor, for {@link SecureTokens}' reason: the input is
 * 256 bits we generated, so there is no dictionary to defend against, and this hash is
 * computed on every visit.
 */
public final class VisitorToken {

    private VisitorToken() {
    }

    /**
     * A new token, for a visitor the platform has not seen before.
     *
     * <p>This is the only moment the value exists in our process — it is returned to
     * the client, hashed, and forgotten.
     */
    public static String mint() {
        return SecureTokens.generate();
    }

    /** The stored form of a token, and the lookup key for one a client presents. */
    public static byte[] hash(String token) {
        return SecureTokens.hash(token);
    }
}
