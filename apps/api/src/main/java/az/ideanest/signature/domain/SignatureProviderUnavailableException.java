package az.ideanest.signature.domain;

/**
 * The signature provider could not be asked, or answered something nobody can read.
 *
 * <p><strong>Distinct from a cancellation, and the distinction is the point</strong> —
 * {@code ProviderUnavailableException} makes the identical argument about charges. A
 * {@link SignatureResult} carrying {@link SignatureOutcome#CANCELLED} or
 * {@link SignatureOutcome#EXPIRED} means the platform knows the citizen did not sign. This
 * means the platform does not know: the request may have reached SİMA, the citizen's phone may
 * have shown the prompt, and the answer may have been lost on the way back.
 *
 * <p>What the caller does with it is not "the signature failed". #428: the creator is told the
 * signing service is unavailable and that their draft is untouched. A submission that failed
 * for an unexplained reason is the outcome this type exists to prevent.
 *
 * @see SignatureOutcome for why an ordinary refusal is a value rather than a throw
 */
public class SignatureProviderUnavailableException extends RuntimeException {

    private final transient SignatureProviderName provider;

    public SignatureProviderUnavailableException(SignatureProviderName provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public SignatureProviderUnavailableException(SignatureProviderName provider, String message) {
        this(provider, message, null);
    }

    /** Which provider could not be reached. Named so a log line does not have to guess. */
    public SignatureProviderName provider() {
        return provider;
    }
}
