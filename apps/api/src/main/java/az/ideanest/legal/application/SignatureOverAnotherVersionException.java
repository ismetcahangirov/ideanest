package az.ideanest.legal.application;

import az.ideanest.shared.legal.AgreementKind;

/**
 * The signature is sound and covers a different text — issue #429.
 *
 * <p>The case the whole hash binding exists to catch: a version was published while somebody's
 * phone was in their pocket, and the signature that comes back is over the text they were shown
 * rather than over the text now in force. It is nobody's fault and it is not satisfiable — the
 * creator signs the current version, and #429 asks for a test that says so.
 *
 * <p>No acceptance is written. A signature over a superseded version binding somebody to the
 * current one is exactly the substitution that a content hash exists to make impossible.
 */
public class SignatureOverAnotherVersionException extends RuntimeException {

    private final transient AgreementKind kind;
    private final int versionInForce;

    public SignatureOverAnotherVersionException(AgreementKind kind, int versionInForce) {
        super("That signature covers a different version of " + kind + "; version " + versionInForce + " is in force");
        this.kind = kind;
        this.versionInForce = versionInForce;
    }

    public AgreementKind kind() {
        return kind;
    }

    public int versionInForce() {
        return versionInForce;
    }
}
