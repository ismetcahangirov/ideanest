package az.ideanest.legal.application;

import az.ideanest.shared.legal.AgreementKind;

/**
 * A signature was asked for by somebody who has not said who they legally are — issue #429.
 *
 * <p>Refused before the provider is called, and refused again before a signature is filed.
 * #430's legal name is the only thing a certificate subject can be matched against, and a
 * signature nobody can tie to a named account proves that <em>a</em> certificate signed the
 * text rather than that this creator did — which V67's header names as the thing that would be
 * worth nothing.
 *
 * <p>The creator's next step is a form they can fill in, which is why this is its own refusal
 * and not a generic one.
 */
public class LegalSubjectRequiredException extends RuntimeException {

    private final transient AgreementKind kind;

    public LegalSubjectRequiredException(AgreementKind kind) {
        super("Signing " + kind + " needs a recorded legal subject to match the certificate against");
        this.kind = kind;
    }

    public AgreementKind kind() {
        return kind;
    }
}
