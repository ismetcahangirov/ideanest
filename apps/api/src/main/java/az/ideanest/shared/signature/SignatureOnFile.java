package az.ideanest.shared.signature;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A signature the platform holds — issue #429, as much of one as crosses a module boundary.
 *
 * <p><strong>No signature value and no certificate.</strong> Those stay in {@code signatures},
 * behind the module that owns the row, and V67's header is why: the table exists to answer
 * exactly one question — "did this person sign this text, and when" — and to answer nothing
 * else. A record that carried the CAdES blob through three modules to reach a screen would make
 * every one of them a place a citizen's certificate material sits.
 *
 * <p>What is here is what the caller needs: an identifier to file against an acceptance, the
 * hash to compare against the version in force, the name to match against #430's legal subject,
 * and the time to show the creator.
 *
 * @param signatureId what {@code document_acceptances.signature_id} points at
 * @param provider which national provider produced it, as a name rather than an enum the legal
 *     module would then hold
 * @param documentHash the hash that was actually signed — compared against the document's own,
 *     never assumed equal to it
 * @param signerName the certificate subject's name. Personal data under §17.4
 * @param signerFin the certificate subject's FİN. Personal data under §17.4
 * @param signedAt when the citizen signed, as the provider reported it
 */
public record SignatureOnFile(
        UUID signatureId,
        String provider,
        String documentHash,
        String signerName,
        String signerFin,
        Instant signedAt) {

    public SignatureOnFile {
        Objects.requireNonNull(signatureId, "signatureId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(documentHash, "documentHash");
        Objects.requireNonNull(signerName, "signerName");
        Objects.requireNonNull(signerFin, "signerFin");
        Objects.requireNonNull(signedAt, "signedAt");
    }

    /** Whether this signature was taken over the text a document currently holds. */
    public boolean covers(String contentHash) {
        return documentHash.equalsIgnoreCase(contentHash);
    }
}
