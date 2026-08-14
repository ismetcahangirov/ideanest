package az.ideanest.auth.domain;

/**
 * RFC 4648 base32, which is the alphabet an authenticator app expects a shared
 * secret in.
 *
 * <p>Base64 would be shorter and is already in the JDK, and it is the wrong
 * answer: every authenticator reads {@code otpauth://} secrets as base32, so a
 * base64 secret would simply not work anywhere. Base32 also avoids the case
 * folding that breaks a secret somebody retyped by hand.
 *
 * <p>Encoding only. Nothing here ever reads a secret back from its printed
 * form: what is stored is the raw bytes, and this exists to show them once.
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static final int BITS_PER_CHARACTER = 5;

    private static final int MASK = 0x1F;

    private Base32() {
    }

    /**
     * The base32 form, unpadded.
     *
     * <p>Padding is omitted because {@code otpauth://} secrets are conventionally
     * unpadded and some authenticators reject the {@code =}. Our secrets are 20
     * bytes, which is a whole number of base32 characters, so there is nothing
     * to pad in the first place.
     */
    public static String encode(byte[] data) {
        StringBuilder encoded = new StringBuilder((data.length * 8 + BITS_PER_CHARACTER - 1) / BITS_PER_CHARACTER);

        int buffer = 0;
        int bitsInBuffer = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= BITS_PER_CHARACTER) {
                encoded.append(ALPHABET[(buffer >>> (bitsInBuffer - BITS_PER_CHARACTER)) & MASK]);
                bitsInBuffer -= BITS_PER_CHARACTER;
            }
        }
        if (bitsInBuffer > 0) {
            // The trailing bits, left-aligned in the last character.
            encoded.append(ALPHABET[(buffer << (BITS_PER_CHARACTER - bitsInBuffer)) & MASK]);
        }

        return encoded.toString();
    }
}
