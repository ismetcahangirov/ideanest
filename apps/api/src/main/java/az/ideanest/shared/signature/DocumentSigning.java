package az.ideanest.shared.signature;

import java.util.Optional;
import java.util.UUID;

/**
 * Sign a hash, and find out what became of the attempt — issue #429, across a module boundary.
 *
 * <p>Two calls, because a national e-signature is not synchronous: {@link #begin} sends a
 * citizen's phone a prompt and returns a code to compare against, and {@link #resolve} says
 * whether they have answered it yet. #428's {@code SignatureProvider} has the same shape for
 * the same reason and this is that shape with the provider's vocabulary removed.
 *
 * <h2>Every call names the signer, and the implementation checks it</h2>
 *
 * <p>{@code resolve} takes a signer as well as a session. A session identifier is a bearer
 * token for somebody else's signature if the only thing needed to read it is the identifier
 * itself, and the row a resolve produces is the platform's record that a named citizen signed a
 * named text. Passing the caller in makes the check possible; leaving it out would make the
 * check impossible to add later without changing every caller.
 */
public interface DocumentSigning {

    /**
     * Whether anything can be signed at all.
     *
     * <p>False when no provider is configured, which is the state the platform is in until
     * #423's personal-data row is answered — {@code SignatureProviders} refuses to point at
     * production SİMA before then. A caller asks this rather than catching an exception,
     * because "signing is not available here yet" is a sentence a creator should be shown
     * calmly rather than as a failure.
     */
    boolean isAvailable();

    /**
     * Start a signing session over a hash.
     *
     * @param signerId the account that will be recorded as having signed
     * @param documentHash SHA-256 of the bytes being signed, lower-case hex. <strong>The hash of
     *     the text, never a description of it</strong> — #429: a signature over a title proves
     *     nothing
     * @param purpose what the citizen is shown on their phone before they decide
     * @param signerFin the citizen's FİN, as their certificate carries it
     * @param signerMobile the number the prompt goes to
     */
    SigningStarted begin(
            UUID signerId, String documentHash, String purpose, String signerFin, String signerMobile);

    /** What became of a session this signer started. */
    SigningProgress resolve(UUID signerId, String sessionId);

    /**
     * Read a signature back by its identifier.
     *
     * <p>Empty when there is no such signature, or when it belongs to somebody else. The caller
     * is told nothing about which of the two, because the difference is only useful to somebody
     * enumerating identifiers.
     */
    Optional<SignatureOnFile> find(UUID signerId, UUID signatureId);
}
