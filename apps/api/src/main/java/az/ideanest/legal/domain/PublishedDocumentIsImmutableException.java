package az.ideanest.legal.domain;

import java.util.UUID;

/**
 * Somebody tried to change a version that has already been published — issue #425.
 *
 * <p><strong>The application's half of a rule the database also holds.</strong> V65's
 * {@code legal_documents_published_is_immutable} trigger is what makes the rule true; this
 * is what makes the refusal readable. A caller that reached the trigger would get a
 * {@code restrict_violation} naming a function, which is the right error in the wrong
 * words for an administrator who has just pressed Save.
 *
 * <p>Both halves are kept. The trigger holds against a hand-written UPDATE during an
 * incident, which is exactly when one would be written; this holds against the console,
 * where the answer is a sentence rather than a stack trace.
 *
 * <p>Rendered as <strong>409 Conflict</strong> by {@code LegalExceptionHandler}: the
 * request is well-formed and the state is what refuses it, and the fix — publish a new
 * version — is a different request rather than a corrected one.
 */
public class PublishedDocumentIsImmutableException extends RuntimeException {

    private final UUID documentId;
    private final DocumentKind kind;
    private final String locale;
    private final int version;

    public PublishedDocumentIsImmutableException(UUID documentId, DocumentKind kind, String locale, int version) {
        super("Version %d of %s/%s is published and cannot be changed".formatted(version, kind, locale));
        this.documentId = documentId;
        this.kind = kind;
        this.locale = locale;
        this.version = version;
    }

    public UUID documentId() {
        return documentId;
    }

    public DocumentKind kind() {
        return kind;
    }

    public String locale() {
        return locale;
    }

    public int version() {
        return version;
    }
}
