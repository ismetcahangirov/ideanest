package az.ideanest.legal.application;

import az.ideanest.legal.domain.DocumentKind;

/**
 * A publication was attempted without the Azerbaijani text — issue #425.
 *
 * <p><strong>The one language that is not optional.</strong> §22.2's documents are the
 * platform's terms in Azerbaijan; the Azerbaijani text is the one that governs, and the
 * other three exist so that a person can read what they are agreeing to. Publishing without
 * it would bind people to a document the platform does not have, and would leave
 * {@code AgreementInForce} with no governing row to point an acceptance at.
 *
 * <p>Refused at publication rather than at drafting, deliberately. An administrator writing
 * the English version first, or asking a translator for the Russian before the Azerbaijani
 * is signed off, is ordinary; what must not happen is the result going live.
 */
public class GoverningTextMissingException extends RuntimeException {

    private final DocumentKind kind;

    public GoverningTextMissingException(DocumentKind kind) {
        super(kind + " cannot be published without its Azerbaijani text, which is the one that governs");
        this.kind = kind;
    }

    public DocumentKind kind() {
        return kind;
    }
}
