package az.ideanest.legal.application;

import az.ideanest.legal.domain.DocumentKind;

/**
 * Publication was asked for and no draft is open — issue #425.
 *
 * <p>Its own refusal rather than a silent no-op, because the two look identical from the
 * console and mean opposite things: an administrator who has just typed a page of terms and
 * pressed Publish, and one who pressed it twice. A no-op would tell the first that their
 * work is live.
 */
public class NothingToPublishException extends RuntimeException {

    private final DocumentKind kind;

    public NothingToPublishException(DocumentKind kind) {
        super("There is no open draft of " + kind + " to publish");
        this.kind = kind;
    }

    public DocumentKind kind() {
        return kind;
    }
}
