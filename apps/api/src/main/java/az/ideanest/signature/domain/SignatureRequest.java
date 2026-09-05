package az.ideanest.signature.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the platform asks a citizen to sign — issue #428.
 *
 * <p><strong>A hash and not a document.</strong> SİMA signs bytes the citizen's own device
 * never has to receive, the text is already immutable in {@code legal_documents}, and V65
 * stores its content hash beside it precisely so that the two cannot disagree about which
 * bytes were signed. Handing the adapter the body instead would mean re-hashing it here and
 * hoping this hash matched the stored one.
 *
 * @param documentHash SHA-256 of the text, lower-case hex — the same shape as
 *     {@code legal_documents.content_hash} and V67's {@code document_hash}, so the three are
 *     comparable without anybody normalising first
 * @param signerFin the citizen the prompt goes to. SİMA addresses a person, not an account:
 *     the platform's user identifier means nothing to it
 * @param signerMobile the number the SİMA application is registered against
 * @param purpose the sentence shown on the citizen's phone above the prompt. Short and in
 *     their language, because a person deciding whether to sign is entitled to know what
 */
public record SignatureRequest(String documentHash, String signerFin, String signerMobile, String purpose) {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public SignatureRequest {
        Objects.requireNonNull(documentHash, "documentHash");
        Objects.requireNonNull(signerFin, "signerFin");
        Objects.requireNonNull(signerMobile, "signerMobile");
        Objects.requireNonNull(purpose, "purpose");
        if (!SHA_256_HEX.matcher(documentHash).matches()) {
            // A programming error rather than an operator's, and worth failing on: a request
            // carrying something that is not a digest is one SIMA would sign anyway, and the
            // row it produced would verify against nothing.
            throw new IllegalArgumentException("A signature is taken over a SHA-256 hex digest");
        }
        if (purpose.isBlank()) {
            throw new IllegalArgumentException("A citizen deciding whether to sign is told what they are signing");
        }
    }
}
