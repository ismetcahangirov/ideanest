package az.ideanest.auth.domain;

import az.ideanest.shared.SecureTokens;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generating and normalising the codes that get somebody back in without their
 * phone.
 *
 * <p>Twenty characters from a thirty-two character alphabet: a hundred bits,
 * from {@link SecureRandom}. That number is what justifies storing them as a
 * plain SHA-256 — see {@link SecureTokens} — and it is why the alphabet is
 * base32's rather than the full keyboard: {@code 0}, {@code 1}, {@code 8} and
 * {@code O}, {@code I}, {@code B} are not both in it, so a code read off paper
 * and typed in cannot be wrong in the way that produces a support ticket.
 *
 * <p>Printed in groups of five with hyphens, and read back with the hyphens,
 * the spaces, and the case ignored. A user copying from paper should not be
 * refused for the shape of what they typed.
 */
public final class RecoveryCodes {

    /** As many as a person can reasonably keep, and no more than they will look after. */
    public static final int COUNT = 10;

    /** Twenty characters at five bits each: a hundred bits per code. */
    private static final int CHARACTERS = 20;

    private static final int GROUP = 5;

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    /** One code, in the form it is shown to the user. */
    public static String generate() {
        StringBuilder code = new StringBuilder(CHARACTERS + CHARACTERS / GROUP);
        for (int index = 0; index < CHARACTERS; index++) {
            if (index > 0 && index % GROUP == 0) {
                code.append('-');
            }
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /**
     * The canonical form: upper case, no separators. Both the stored hash and
     * every lookup go through here, so a code typed with the hyphens and one
     * typed without are the same code rather than two.
     */
    public static String normalise(String code) {
        StringBuilder normalised = new StringBuilder(CHARACTERS);
        String uppercased = code.toUpperCase(Locale.ROOT);
        for (int index = 0; index < uppercased.length(); index++) {
            char character = uppercased.charAt(index);
            if (character != '-' && character != ' ') {
                normalised.append(character);
            }
        }
        return normalised.toString();
    }

    /** The stored form of a code, and the lookup key for one presented to us. */
    public static byte[] hash(String code) {
        return SecureTokens.hash(normalise(code));
    }
}
