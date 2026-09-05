package az.ideanest.signature.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A signature the platform holds — V67's row as the domain sees it, issue #428.
 *
 * <p>A record rather than an entity, deliberately. V67's table is append-only: nothing in it
 * is ever updated, there is no lifecycle, and the one mutable thing about a signature — whether
 * it still verifies — is a question asked of the provider rather than a column. An entity would
 * offer a dirty-checked flush for a row that must never be flushed twice.
 *
 * <p>{@code SignatureProvider.verify} takes one of these and a hash, which is what makes
 * "check a signature we already hold, against the document it claims to sign" expressible
 * without the caller having to know what a certificate is.
 *
 * @param documentHash the bytes that were actually signed. <strong>Not assumed equal to the
 *     hash of the document this is filed against</strong> — {@link Verification} is where the
 *     two are compared, and V67's header says why they are separate statements
 * @param signatureValue the signature itself, base64. What SİMA produced and what a verifier
 *     is handed back
 * @param subject who the certificate says signed
 * @param signedAt when the citizen signed, as the provider reported it. Distinct from when the
 *     platform learned, which is V67's {@code created_at}
 */
public record StoredSignature(
        SignatureProviderName provider,
        String providerSessionId,
        String documentHash,
        String signatureValue,
        CertificateSubject subject,
        Instant signedAt) {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public StoredSignature {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(documentHash, "documentHash");
        Objects.requireNonNull(signatureValue, "signatureValue");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(signedAt, "signedAt");
        if (!SHA_256_HEX.matcher(documentHash).matches()) {
            throw new IllegalArgumentException("A signature is taken over a SHA-256 hex digest");
        }
        if (signatureValue.isBlank()) {
            throw new IllegalArgumentException("A signature with no value is not one");
        }
    }
}
