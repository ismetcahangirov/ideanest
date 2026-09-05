package az.ideanest.legal.application;

import az.ideanest.legal.domain.DocumentKind;

/**
 * A document §22.2 requires the platform to have has not been published — issue #425.
 *
 * <p><strong>404, and it is an honest one.</strong> The route exists and the kind is one of
 * the eight; what is missing is the text, because nobody has written it yet. That is the
 * state of this repository until #439 seeds the words, and a 404 says so more truthfully
 * than an empty document would.
 *
 * <p><strong>Not a refusal anything is gated on.</strong> Reading a document that has not
 * been published fails; <em>doing</em> the thing that document would have governed does
 * not — {@code Agreements} argues at length why the legal gates fail open where the
 * subscription gate fails closed. The two behaviours look inconsistent side by side and are
 * not: a reader asking for a page that does not exist should be told, and a creator should
 * not be refused a submission for want of a document nobody wrote.
 */
public class DocumentNotPublishedException extends RuntimeException {

    private final DocumentKind kind;

    public DocumentNotPublishedException(DocumentKind kind) {
        super("No version of " + kind + " has been published");
        this.kind = kind;
    }

    public DocumentKind kind() {
        return kind;
    }
}
