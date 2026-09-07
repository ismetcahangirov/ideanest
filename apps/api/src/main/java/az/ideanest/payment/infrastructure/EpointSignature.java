package az.ideanest.payment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Epoint's signing scheme, in one place — §9.3's R-07.
 *
 * <pre>
 *   data      = base64(utf8(json_string))
 *   signature = base64(sha1(private_key + data + private_key))
 * </pre>
 *
 * <p>Taken from Epoint's specification version 1.0.3 and transcribed in
 * {@code docs/providers/epoint.md}. Three details are load-bearing and each of them is a
 * way an integration silently fails to verify anything:
 *
 * <ul>
 *   <li><strong>The digest is base64 of the twenty raw bytes</strong>, not of its hex
 *       rendering. Epoint's PHP example is {@code sha1($string, 1)} — the second argument is
 *       what asks for binary. An implementation that base64s the hex string produces a
 *       forty-character digest that is the wrong length and never matches.
 *   <li><strong>The key is concatenated on both sides.</strong> It is not HMAC, and it does
 *       not behave like one; this class exists so that nobody reimplements it as HMAC-SHA1
 *       because that is what every other provider uses.
 *   <li><strong>The signature is symmetric.</strong> The same construction signs an outgoing
 *       request and verifies an incoming callback, so anybody holding the private key can mint
 *       a delivery. That is Epoint's design and not a choice available here; what follows from
 *       it is that the key is the entire authentication of an endpoint that is otherwise
 *       unauthenticated, which is why {@code Redaction} carries it.
 * </ul>
 *
 * <p>SHA-1 is not a defensible choice in 2026 and this class does not pretend otherwise. It is
 * the scheme the provider offers; the alternative is not integrating. It is recorded as a
 * finding in {@code docs/providers/epoint.md} rather than hidden behind a helper that looks
 * modern.
 */
final class EpointSignature {

    private EpointSignature() {
    }

    /** The {@code data} field: base64 of the JSON, exactly as Epoint reads it. */
    static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code signature} field for a {@code data} value. */
    static String sign(String privateKey, String data) {
        String concatenated = privateKey + data + privateKey;
        return Base64.getEncoder().encodeToString(sha1(concatenated.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Whether a delivery's signature is the one this private key produces over its data.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which is constant-time. An ordinary
     * {@code equals} on a signature is a timing oracle: the caller controls the value, can
     * send as many as it likes, and learns the correct prefix one character at a time. That is
     * a real attack on an unauthenticated endpoint even though it is a slow one, and the fix
     * costs nothing.
     */
    static boolean verify(String privateKey, String data, String presented) {
        if (data == null || presented == null) {
            return false;
        }
        byte[] expected = sign(privateKey, data).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** The base64 in a {@code data} field, decoded back to the JSON Epoint signed. */
    static String decode(String data) {
        return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-1. If this one does not, the platform cannot talk to Epoint
            // at all, and a checked exception on every call site would be ceremony around an
            // impossibility.
            throw new IllegalStateException("This JVM has no SHA-1, and Epoint's API is signed with it", e);
        }
    }
}
