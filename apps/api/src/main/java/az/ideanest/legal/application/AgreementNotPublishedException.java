package az.ideanest.legal.application;

import az.ideanest.shared.legal.AgreementKind;

/**
 * Somebody tried to accept an agreement that has not been published — issue #425.
 *
 * <p><strong>Deliberately not the same behaviour as the gates.</strong> A gate that lets a
 * submission through because no creator agreement exists is right — {@code Agreements}
 * argues why at length. An <em>acceptance</em> of a document that does not exist is
 * different: there is no text, so there is nothing the person can have read, and recording
 * it would produce the one thing this table must never hold, which is an acceptance of
 * nothing.
 *
 * <p>409 rather than 404: the kind is a real one and the route is right; what is missing is
 * a published version, which is a state rather than a typo.
 */
public class AgreementNotPublishedException extends RuntimeException {

    private final AgreementKind kind;

    public AgreementNotPublishedException(AgreementKind kind) {
        super("No version of " + kind + " is in force, so there is nothing to accept");
        this.kind = kind;
    }

    public AgreementKind kind() {
        return kind;
    }
}
