package az.ideanest.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque tokens: verification links, refresh tokens, two-factor challenges, and
 * collaborator invitations.
 *
 * <p><strong>In {@code shared} because a second module needs it.</strong> It
 * began in {@code auth.domain}, and #38 made the campaign editor issue an
 * invitation link with the same properties. A module may not reach into another
 * module's domain, so the choice was between moving this and writing a second
 * copy of it inside {@code project} — and two implementations of "how we generate
 * and store an opaque token" is exactly the duplication that ends with one of
 * them being weakened by somebody who did not know the other existed.
 *
 * <p>256 bits from {@link SecureRandom}, encoded URL-safe so the value survives
 * being pasted into an address bar, and stored only as its SHA-256.
 *
 * <p>The hash is unsalted and has no work factor, which would be indefensible
 * for a password and is correct here: the input is 256 bits we generated, so
 * there is no dictionary and no rainbow table, and this hash is computed on
 * every single refresh. Argon2 on that path would be a self-inflicted denial of
 * service.
 */
public final class SecureTokens {

    /** 256 bits. Guessing is not a threat model at this size; theft is. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {
    }

    /** A new token. This is the only moment the value exists in our process. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** The stored form of a token, and the lookup key for one presented to us. */
    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. If this happens the platform is broken in
            // a way no fallback would survive.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
