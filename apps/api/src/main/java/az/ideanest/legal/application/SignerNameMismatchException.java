package az.ideanest.legal.application;

import az.ideanest.shared.compliance.RejectionReason;
import az.ideanest.shared.legal.AgreementKind;

/**
 * The certificate names somebody other than the account — issue #429.
 *
 * <p><strong>Not a silent pass.</strong> #429 is explicit that a mismatch is a refusal with a
 * reason, and that the reason comes from the closed set
 * {@code identity_verifications.rejection_reason} already uses: {@link
 * RejectionReason#MISMATCHED_NAME} is a value V58 defined for exactly this. Reusing it rather
 * than inventing a second vocabulary is the point — two vocabularies for one idea is one too
 * many.
 *
 * <p>The certificate's name is deliberately not carried on the exception. It is a third party's
 * personal data under §17.4, it goes in the audit entry where a reader has to be authorised to
 * see it, and a creator who signed with somebody else's certificate does not need it read back
 * to them.
 */
public class SignerNameMismatchException extends RuntimeException {

    private final transient AgreementKind kind;
    private final transient RejectionReason reason;

    public SignerNameMismatchException(AgreementKind kind, RejectionReason reason) {
        super("The certificate that signed " + kind + " names somebody other than this account");
        this.kind = kind;
        this.reason = reason;
    }

    public AgreementKind kind() {
        return kind;
    }

    public RejectionReason reason() {
        return reason;
    }
}
