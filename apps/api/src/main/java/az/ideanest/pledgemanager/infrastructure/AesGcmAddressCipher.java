package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.PledgeManagerProperties;
import az.ideanest.pledgemanager.application.AddressCipher;
import az.ideanest.pledgemanager.application.AddressUnreadableException;
import az.ideanest.pledgemanager.domain.PostalAddress;
import az.ideanest.pledgemanager.domain.SealedAddress;
import az.ideanest.pledgemanager.domain.ShippingAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AddressCipher} as AES-256-GCM over a JSON rendering of the address.
 *
 * <h2>Why the key is the application's and not PostgreSQL's</h2>
 *
 * <p>§17.2 names {@code shipping_addresses} specifically, and disk encryption does not
 * answer it: a backup, a read replica, a {@code SELECT *} in a support console and an
 * SQL injection all see plaintext through it. {@code pgcrypto} was the other candidate
 * and is worse for a reason that has nothing to do with its cryptography —
 * {@code pgp_sym_encrypt} takes the passphrase as a query argument, which lands in
 * {@code pg_stat_statements}, in the slow query log, and in any statement log an
 * operator switches on during an incident. The key never reaching the database is the
 * property, and it is only a property if nothing sends it there.
 *
 * <h2>GCM, and what that decides</h2>
 *
 * <p>Authenticated encryption, so a row somebody edited in the database fails to open
 * rather than opening as a different address. A 12-byte nonce, which is what GCM is
 * specified for — any other length forces an extra hash inside the cipher and buys
 * nothing — drawn from {@link SecureRandom} on every seal, including when the same
 * address is stored twice. Nonce reuse under one key is the failure that breaks GCM
 * completely rather than gradually, and "the address did not change" is exactly the
 * case where a naive implementation would be tempted to keep the old one.
 *
 * <h2>What is deliberately not authenticated</h2>
 *
 * <p>No additional authenticated data — not the pledge identifier, not the campaign.
 * It was considered: binding the ciphertext to its row would mean an address moved
 * between rows fails to open. It is not done because the platform has no operation
 * that moves one, and AAD that nothing varies is a parameter to get wrong during a
 * restore. The row's own foreign keys are what tie an address to a pledge.
 *
 * <h2>JSON inside the envelope</h2>
 *
 * <p>The eight fields are serialised as JSON and the whole document is sealed. A
 * field-delimited format would be smaller and would break the day an address line
 * legitimately contains the delimiter; JSON is what the rest of the service already
 * uses, and a field added to {@link PostalAddress} reads back as null on rows sealed
 * before it existed, which is the correct answer for an optional one.
 */
@Component
public class AesGcmAddressCipher implements AddressCipher {

    /** GCM's authentication tag, in bits. 128 is the maximum and the only one worth choosing. */
    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String ALGORITHM = "AES";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper json;
    private final String primaryKeyId;
    private final Map<String, byte[]> keys;

    public AesGcmAddressCipher(ObjectMapper json, PledgeManagerProperties properties) {
        this.json = json;
        PledgeManagerProperties.Addresses addresses = properties.addresses();
        this.primaryKeyId = addresses.primaryKeyId();
        this.keys = addresses.decodedKeys();
    }

    @Override
    public SealedAddress seal(PostalAddress address) {
        byte[] nonce = new byte[ShippingAddress.NONCE_LENGTH];
        RANDOM.nextBytes(nonce);

        byte[] plaintext = json.writeValueAsBytes(address).clone();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyNamed(primaryKeyId), ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return new SealedAddress(cipher.doFinal(plaintext), nonce, primaryKeyId);
        } catch (GeneralSecurityException e) {
            // A failure here is a misconfigured key rather than a bad address, so it
            // must not be reported to the backer as a validation problem. The message
            // names the key label and never the address.
            throw new AddressUnreadableException("Could not encrypt an address under key " + primaryKeyId, e);
        } finally {
            // The plaintext copy this method made, gone before the method returns.
            // The record itself and whatever Jackson allocated internally are not
            // reachable from here; this is the one buffer that is, and clearing it is
            // cheap.
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public PostalAddress open(SealedAddress sealed) {
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyNamed(sealed.keyId()), ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            plaintext = cipher.doFinal(sealed.ciphertext());
        } catch (GeneralSecurityException e) {
            // An authentication failure means the row was changed underneath us or the
            // wrong key is configured under that label. Both are operational, and
            // neither is anything a retry will fix.
            throw new AddressUnreadableException("A stored address failed to decrypt under key " + sealed.keyId(), e);
        }

        try {
            return json.readValue(plaintext, PostalAddress.class);
        } catch (JacksonException e) {
            throw new AddressUnreadableException("A decrypted address is not the document this service writes", e);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * The key material behind a label.
     *
     * @throws AddressUnreadableException when the deployment has no such key. A row
     *     sealed under a key that has been removed from the configuration is
     *     unreadable, and saying so is the only honest answer — the alternative,
     *     falling back to the primary key, would fail the authentication tag anyway
     *     and would report it as tampering
     */
    private byte[] keyNamed(String keyId) {
        byte[] key = keys.get(keyId);
        if (key == null) {
            throw new AddressUnreadableException(
                    "No address encryption key is configured under the label " + keyId);
        }
        return key;
    }
}
