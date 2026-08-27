package az.ideanest.verification.domain;

import java.util.Objects;

/**
 * A document as it is stored: ciphertext, nonce, and the label of the key that sealed it —
 * issue #105.
 *
 * <p>The same three-part envelope {@code SealedAddress} uses, and deliberately the same
 * shape: one platform with two encryption schemes is one platform whose second scheme is
 * the one nobody reviews. What differs is only what is inside — an address is a JSON
 * document and this is a photograph.
 *
 * <p><strong>It never holds the plaintext.</strong> A record carrying both would be one
 * that ends up in a log line, in a heap dump, and in a debugger's variables pane.
 */
public record SealedDocument(byte[] ciphertext, byte[] nonce, String keyId) {

    /** GCM's nonce, in bytes. Twelve is what GCM is specified for, and the schema holds it. */
    public static final int NONCE_LENGTH = 12;

    public SealedDocument {
        Objects.requireNonNull(ciphertext, "A sealed document has ciphertext");
        Objects.requireNonNull(nonce, "A sealed document has a nonce");
        Objects.requireNonNull(keyId, "A sealed document names the key that sealed it");

        if (nonce.length != NONCE_LENGTH) {
            throw new IllegalArgumentException("A GCM nonce is " + NONCE_LENGTH + " bytes");
        }
        // Copied on the way in and on the way out. An array is mutable, and a caller that
        // kept a reference could change a stored ciphertext after it was constructed.
        ciphertext = ciphertext.clone();
        nonce = nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public String toString() {
        // Lengths and the key label. Never the bytes: a ciphertext in a log is a
        // ciphertext in a log aggregator, next to the row that names its key.
        return "SealedDocument[bytes=" + ciphertext.length + ", keyId=" + keyId + "]";
    }
}
