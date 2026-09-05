package az.ideanest.signature.domain;

import java.util.Objects;

/**
 * Whether a signature the platform holds still stands up — issue #428.
 *
 * <p><strong>Three questions and not one boolean</strong>, because they fail for different
 * reasons and the answers lead somewhere different. A signature whose cryptography is sound
 * but whose hash names another document is not a forgery; it is a filing error, and it is the
 * case #428 asks for a test of by name. A signature whose certificate had been revoked at
 * signing time is neither.
 *
 * <p>{@link #isValid()} is the conjunction, offered so that a caller who genuinely only wants
 * the boolean does not compute it themselves and get the third condition wrong.
 *
 * @param signatureIsSound the cryptography verifies against the certificate
 * @param hashMatches the bytes signed are the bytes of the document it was checked against.
 *     V67's header: "they should be equal, and the moment they are not is the moment somebody
 *     has to know"
 * @param certificateWasValid the certificate was in force and not revoked when it signed.
 *     Asked about the signing time and not about now — a certificate that expired last month
 *     did not retroactively unsign a contract from last year
 * @param detail what the provider said, for the log. Never a reason shown to a person
 */
public record Verification(
        boolean signatureIsSound, boolean hashMatches, boolean certificateWasValid, String detail) {

    public Verification {
        Objects.requireNonNull(detail, "detail");
    }

    /** All three. The only question most callers have. */
    public boolean isValid() {
        return signatureIsSound && hashMatches && certificateWasValid;
    }

    public static Verification valid() {
        return new Verification(true, true, true, "");
    }

    /**
     * Sound, current, and over the wrong bytes.
     *
     * <p>Named rather than assembled at each call site because it is the interesting failure:
     * a real signature filed against a document it does not sign.
     */
    public static Verification wrongDocument(String detail) {
        return new Verification(true, false, true, detail);
    }
}
