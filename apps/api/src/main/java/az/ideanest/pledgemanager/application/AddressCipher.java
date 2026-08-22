package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.PostalAddress;
import az.ideanest.pledgemanager.domain.SealedAddress;

/**
 * The only route between a readable address and a stored one — §17.2.
 *
 * <p>An interface with one implementation, and the interface is doing work: it is the
 * seam that lets {@code ShippingAddressService} be tested without a configured key,
 * and it is the declaration that <strong>nothing else on the platform encrypts an
 * address</strong>. A second call site with its own {@code Cipher.getInstance} would
 * be a second set of parameters, free to pick a different mode, and the first sign
 * would be rows that cannot be decrypted.
 *
 * <p>Declared in {@code application} rather than {@code domain} because it is a port
 * to a keystore, and implemented in {@code infrastructure} where the key material is
 * read.
 */
public interface AddressCipher {

    /**
     * Encrypts under the current primary key.
     *
     * <p>A fresh nonce every time, including when the same address is stored twice.
     * Reusing one under the same key is the failure that breaks GCM completely rather
     * than gradually.
     */
    SealedAddress seal(PostalAddress address);

    /**
     * Decrypts, whichever key sealed it.
     *
     * @throws AddressUnreadableException when the key named on the row is not
     *     configured, or when the ciphertext fails its authentication tag. Both are
     *     operational faults rather than user errors — a misconfigured deployment and
     *     a tampered or corrupted row — and neither is something a backer can fix by
     *     retyping
     */
    PostalAddress open(SealedAddress sealed);
}
