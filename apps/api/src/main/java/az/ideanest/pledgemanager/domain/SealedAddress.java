package az.ideanest.pledgemanager.domain;

/**
 * An encrypted address and everything needed to read it back.
 *
 * <p>Three fields because AES-GCM needs three: the ciphertext with its authentication
 * tag, the nonce it was produced under, and — this platform's addition — the label of
 * the key that produced it.
 *
 * <p><strong>The nonce is stored beside the ciphertext rather than prepended to
 * it.</strong> Both are conventional; this one is chosen because a reader of the
 * schema can see that a nonce exists, and a reader of the code cannot forget to split
 * it off. A twelve-byte prefix silently treated as ciphertext decrypts to nothing and
 * looks exactly like a corrupted row.
 *
 * <p><strong>{@code keyId} is what makes rotation possible.</strong> Without it,
 * changing the key means decrypting and rewriting every row in one transaction before
 * anything can start, and a failure halfway through leaves a table nobody can read.
 * With it, a deployment makes a new key primary, keeps the old one readable, and rows
 * migrate as they are written. It is a short opaque label rather than the key or a
 * hash of it — a hash would be a fixed value an attacker holding the ciphertext could
 * grind against.
 */
public record SealedAddress(byte[] ciphertext, byte[] nonce, String keyId) {

    @Override
    public String toString() {
        // Lengths and the key label, never the bytes. A ciphertext in a log is not a
        // disclosure today and is one the moment a key leaks.
        return "SealedAddress[" + ciphertext.length + " bytes, key=" + keyId + "]";
    }
}
