package az.ideanest.auth.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords, over {@code javax.crypto}.
 *
 * <p>Written out rather than taken from a library. {@code dev.samstevens.totp}
 * is what §15.1 names, and its last commit is from November 2020 with no
 * release since 1.7.1 — an unmaintained dependency sitting on the
 * authentication path, pulling a QR-code generator in with it for a picture we
 * render on the client anyway. The specification is HMAC, a counter, and a
 * truncation, which is what this file is.
 *
 * <p><strong>SHA-1, six digits, thirty seconds.</strong> Not a security
 * judgement about SHA-1 — HMAC-SHA1 is unaffected by the collision attacks that
 * retired the bare hash — but an interoperability one: every authenticator
 * reads these defaults, and several silently assume them whatever the
 * {@code otpauth://} parameters say. A secret that produces codes the user's
 * app disagrees with is a lockout.
 *
 * <p><strong>One step of skew either side.</strong> A phone whose clock is off
 * by twenty seconds is ordinary; a user who starts typing at the end of a
 * window is more ordinary still. That makes the acceptance window ninety
 * seconds rather than thirty, and three codes valid at once instead of one, so
 * a guess is three in a million rather than one. Rate limiting is what makes
 * that number mean something, and the caller is required to apply it. Two steps
 * either side is the other common choice and it triples the exposure of a code
 * read over somebody's shoulder for a case — a clock a minute out — that is
 * really a broken clock rather than drift.
 */
public final class Totp {

    /** 160 bits, the key size RFC 4226 §4 asks for with HMAC-SHA1. */
    public static final int SECRET_BYTES = 20;

    public static final int DIGITS = 6;

    public static final Duration PERIOD = Duration.ofSeconds(30);

    /** How many steps either side of now are accepted. See the class comment. */
    public static final int SKEW_STEPS = 1;

    public static final String ALGORITHM = "HmacSHA1";

    private static final int MODULUS = 1_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new shared secret. This is the only moment it is generated. */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** Which time step {@code at} falls in. The counter the code is derived from. */
    public static long stepAt(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), PERIOD.toSeconds());
    }

    /** The code for one step, zero-padded to {@link #DIGITS}. */
    public static String codeAt(byte[] secret, long step) {
        byte[] mac = hmac(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());

        // RFC 4226 §5.3 dynamic truncation: the low nibble of the last byte
        // picks where to read, so the digits do not always come from the same
        // part of the MAC.
        int offset = mac[mac.length - 1] & 0x0F;
        int binary = ((mac[offset] & 0x7F) << 24)
                | ((mac[offset + 1] & 0xFF) << 16)
                | ((mac[offset + 2] & 0xFF) << 8)
                | (mac[offset + 3] & 0xFF);

        return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % MODULUS);
    }

    /**
     * The step whose code matches, or empty.
     *
     * <p>Returns the step rather than a boolean because the caller has to record
     * it: a code that was accepted must not be accepted again, and "again" can
     * only be defined against the step it belonged to.
     *
     * <p>Every candidate step is compared even after one matches. The work is
     * three HMACs of twenty bytes, and returning early would make the response
     * time say which of the three windows the code came from.
     */
    public static OptionalLong verify(byte[] secret, String presented, Instant now) {
        String candidate = normalise(presented);
        if (candidate.length() != DIGITS) {
            return OptionalLong.empty();
        }

        long currentStep = stepAt(now);
        long matched = Long.MIN_VALUE;
        for (long step = currentStep - SKEW_STEPS; step <= currentStep + SKEW_STEPS; step++) {
            if (constantTimeEquals(codeAt(secret, step), candidate)) {
                matched = step;
            }
        }

        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    /**
     * Spaces out, digits kept. Authenticators display {@code 123 456} and people
     * type what they see; refusing that is a support ticket rather than a
     * control.
     */
    private static String normalise(String presented) {
        StringBuilder digits = new StringBuilder(DIGITS);
        for (int index = 0; index < presented.length(); index++) {
            char character = presented.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            } else if (character != ' ' && character != '-') {
                // Anything else means this is not a code at all, and padding it
                // out to six digits would be guessing on the user's behalf.
                return "";
            }
        }
        return digits.toString();
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), presented.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] secret, byte[] counter) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(counter);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Every JVM ships HMAC-SHA1, and the key is one we generated. Either
            // failure means the platform is broken in a way no fallback fixes.
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }
    }
}
