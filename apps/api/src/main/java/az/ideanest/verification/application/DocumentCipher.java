package az.ideanest.verification.application;

import az.ideanest.verification.domain.SealedDocument;

/**
 * The only route between a readable identity document and a stored one — §17.4, issue #105.
 *
 * <p>An interface with one implementation, and the interface is doing work: it is the seam
 * that lets {@code IdentityVerifications} be tested without a configured key, and it is the
 * declaration that <strong>nothing else on the platform encrypts a document</strong>. A
 * second call site with its own {@code Cipher.getInstance} would be a second set of
 * parameters, free to pick a different mode, and the first sign would be rows that cannot
 * be decrypted.
 *
 * <p>Declared in {@code application} rather than {@code domain} because it is a port to a
 * keystore, and implemented in {@code infrastructure} where the key material is read. The
 * same arrangement {@code AddressCipher} has, on purpose.
 */
public interface DocumentCipher {

    /** Whether this deployment can store a document at all. */
    boolean isConfigured();

    /**
     * Encrypts under the current primary key.
     *
     * <p>A fresh nonce every time, including for the same bytes twice. Reusing one under
     * the same key is the failure that breaks GCM completely rather than gradually.
     */
    SealedDocument seal(byte[] document);

    /**
     * Decrypts, whichever key sealed it.
     *
     * @throws DocumentUnreadableException when the key named on the row is not configured,
     *     or when the ciphertext fails its authentication tag
     */
    byte[] open(SealedDocument sealed);
}
