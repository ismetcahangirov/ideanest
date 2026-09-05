package az.ideanest.legal.application;

import az.ideanest.shared.legal.AgreementKind;

/**
 * The version offered is not the version in force — issues #425, #427.
 *
 * <p><strong>The case the version field exists for.</strong> A client could send nothing and
 * the server could record whatever is current, which would be simpler and would record a
 * lie: it would say somebody accepted a version published while their page was open. So the
 * client says which text it showed, and a mismatch is refused rather than quietly upgraded.
 *
 * <p>The recovery is always the same and the client can always perform it without asking
 * anybody: reload, show the new text, accept again.
 */
public class AgreementVersionStaleException extends RuntimeException {

    private final AgreementKind kind;
    private final int inForce;
    private final int offered;

    public AgreementVersionStaleException(AgreementKind kind, int inForce, int offered) {
        super("%s version %d is in force; version %d was offered".formatted(kind, inForce, offered));
        this.kind = kind;
        this.inForce = inForce;
        this.offered = offered;
    }

    public AgreementKind kind() {
        return kind;
    }

    public int inForce() {
        return inForce;
    }

    public int offered() {
        return offered;
    }
}
