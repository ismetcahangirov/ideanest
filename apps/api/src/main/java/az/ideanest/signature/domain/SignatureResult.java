package az.ideanest.signature.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What became of a signing session — issue #428.
 *
 * <p><strong>A cancellation is a result and not an exception</strong>, which is §9.4's
 * distinction applied to a different provider. A citizen who declines the prompt or lets it
 * expire has done something ordinary and retryable; modelling that as a throw would put the
 * outcome into a message string and would make the ordinary path the exceptional one. What
 * <em>is</em> a throw is {@link SignatureProviderUnavailableException}, because then the
 * platform does not know what the citizen did.
 *
 * <p><strong>The signature is present exactly when the outcome is {@code SIGNED}</strong>, and
 * the constructor refuses anything else. Either half alone is a result a caller would misread:
 * a signature on a cancelled session is a row somebody would store, and a {@code SIGNED} with
 * nothing attached is a submission that thinks it is complete.
 *
 * @param failureDetail why, in the provider's words, for the log and the support conversation.
 *     Never shown to the citizen — {@code ChargeResult.failureMessage} makes the same point:
 *     a provider's message is written for an integrator, and the person on the other end is
 *     told "not signed yet"
 */
public record SignatureResult(SignatureOutcome outcome, StoredSignature signature, String failureDetail) {

    public SignatureResult {
        Objects.requireNonNull(outcome, "A result says what became of the session");
        boolean signed = outcome == SignatureOutcome.SIGNED;
        if (signed != (signature != null)) {
            throw new IllegalArgumentException(
                    signed
                            ? "A signed session carries the signature it produced"
                            : "Only a signed session carries a signature");
        }
    }

    /** The signature, when there is one. */
    public Optional<StoredSignature> signatureIfSigned() {
        return Optional.ofNullable(signature);
    }

    /** Whether this session is still waiting on the citizen. What a poll branches on. */
    public boolean isPending() {
        return outcome == SignatureOutcome.PENDING;
    }

    public static SignatureResult signed(StoredSignature signature) {
        return new SignatureResult(SignatureOutcome.SIGNED, signature, null);
    }

    public static SignatureResult pending() {
        return new SignatureResult(SignatureOutcome.PENDING, null, null);
    }

    public static SignatureResult cancelled(String detail) {
        return new SignatureResult(SignatureOutcome.CANCELLED, null, detail);
    }

    public static SignatureResult expired(String detail) {
        return new SignatureResult(SignatureOutcome.EXPIRED, null, detail);
    }
}
