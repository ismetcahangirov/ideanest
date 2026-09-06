package az.ideanest.shared.signature;

import java.util.Objects;
import java.util.Optional;

/**
 * What became of a signing session — issue #429.
 *
 * <p>Mirrors #428's {@code SignatureResult} with the provider's vocabulary removed, and keeps
 * its central distinction intact: <strong>a citizen who cancels or lets a session expire is an
 * outcome, not a failure.</strong> Those are values here. A SİMA that cannot be reached is an
 * exception, thrown by the adapter and not turned into one of these — the difference is between
 * "not signed yet" on a screen and an incident.
 *
 * @param state signed, still waiting, cancelled or expired
 * @param signature what was signed and by whom, present only when {@code state} is
 *     {@link SigningState#SIGNED}
 * @param detail what the provider said about a cancellation or an expiry, for a log rather than
 *     for a creator: it is the provider's words, in the provider's language
 */
public record SigningProgress(SigningState state, SignatureOnFile signature, String detail) {

    public SigningProgress {
        Objects.requireNonNull(state, "A progress says what became of the session");
        boolean signed = state == SigningState.SIGNED;
        if (signed != (signature != null)) {
            throw new IllegalArgumentException(
                    signed
                            ? "A signed session carries the signature it produced"
                            : "Only a signed session carries a signature");
        }
    }

    public Optional<SignatureOnFile> signed() {
        return Optional.ofNullable(signature);
    }

    public boolean isPending() {
        return state == SigningState.PENDING;
    }

    public static SigningProgress signed(SignatureOnFile signature) {
        return new SigningProgress(SigningState.SIGNED, signature, null);
    }

    public static SigningProgress pending() {
        return new SigningProgress(SigningState.PENDING, null, null);
    }

    public static SigningProgress cancelled(String detail) {
        return new SigningProgress(SigningState.CANCELLED, null, detail);
    }

    public static SigningProgress expired(String detail) {
        return new SigningProgress(SigningState.EXPIRED, null, detail);
    }
}
