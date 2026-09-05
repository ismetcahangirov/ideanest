package az.ideanest.shared.legal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The version of an agreement that governs right now, as the answer crosses a module
 * boundary — issue #425.
 *
 * <p><strong>It names a version, not a body.</strong> The gates do not render the document
 * and must not carry a megabyte of text through a refusal; what they need is enough to send
 * somebody to the right page and enough for the refusal to be checkable afterwards. #439
 * renders the text, from the same rows, on its own routes.
 *
 * <p><strong>{@link #documentId} is the governing text's identifier, which is the
 * Azerbaijani one.</strong> V65 publishes a version in every language it has been
 * translated into, under one version number and one effective date, and refuses to publish
 * at all without the Azerbaijani draft — because that is the text that governs and the
 * other three exist so that a person can read what they are agreeing to. An acceptance
 * therefore points at this row whichever language the person read, and "which version did
 * they accept" has one answer rather than four.
 *
 * @param kind which agreement
 * @param documentId the governing (Azerbaijani) version's row, which an acceptance names
 * @param version the number this version carries in every language it exists in. What a
 *     client sends back to say which text it showed, and what a refusal names
 * @param effectiveFrom when it started governing. Never in the future for a value returned
 *     here — a version published today to take effect in a fortnight is not yet in force
 */
public record AgreementInForce(AgreementKind kind, UUID documentId, int version, Instant effectiveFrom) {

    public AgreementInForce {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (version < 1) {
            throw new IllegalArgumentException("A published version starts at 1; got " + version);
        }
    }
}
