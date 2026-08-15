package az.ideanest.reward.domain;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The shareable secret of a hidden reward tier.
 *
 * <p>256 bits from {@link SecureRandom}, URL-safe, because the value is pasted
 * into an address bar by whoever the creator sent it to.
 *
 * <p><strong>Stored in the clear</strong>, unlike the tokens
 * {@code auth.domain.SecureTokens} produces, and the difference is what the token
 * is for. A verification token authenticates a person once and is never shown
 * again, so it is stored as a hash and a leaked table is worthless. This one is a
 * capability the creator hands out repeatedly — to a mailing list, in a private
 * update — so the creator has to be able to read back the link they are supposed
 * to be sending, and a hash would make that impossible.
 *
 * <p>Written here rather than shared with the auth module's generator because
 * {@code auth.domain} is another module's internals, which is the boundary
 * {@code ModuleBoundaryTests} keeps. Sixteen lines of {@link SecureRandom} is a
 * cheaper price than a dependency between two modules that have nothing else to
 * say to each other.
 */
public final class SecretTokens {

    /** 256 bits. A secret tier's link is guessed by nobody at this size. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** No padding: {@code =} in a URL is a query separator waiting to be mangled. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecretTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
