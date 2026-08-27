package az.ideanest.verification.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What may be submitted, and by whom — issue #105.
 *
 * <p>The mapping from {@link SubjectKind} lives here rather than in the service, so that
 * "a company cannot submit a passport as its registration" is one statement rather than a
 * check in the controller and a different one further down.
 *
 * <p>The set is closed and it is closed in the schema too
 * ({@code identity_documents_kind_known}). What is <em>not</em> here is a document number,
 * a name, or a date of birth: this platform stores the photograph and the decision, and
 * nothing it could transcribe from the document is worth holding when the decision is.
 */
public enum DocumentKind {

    /** An identity card, the side with the photograph. */
    ID_CARD_FRONT(SubjectKind.INDIVIDUAL),

    /**
     * The other side.
     *
     * <p>A separate kind rather than a second file of one kind, because "the back is
     * missing" is the commonest reason a submission is {@code INCOMPLETE} and a reviewer
     * should be able to see which side arrived without opening either.
     */
    ID_CARD_BACK(SubjectKind.INDIVIDUAL),

    PASSPORT(SubjectKind.INDIVIDUAL),

    /** For a creator resident here on a permit rather than on a national card. */
    RESIDENCE_PERMIT(SubjectKind.INDIVIDUAL),

    /** A company's registration extract. */
    COMPANY_REGISTRATION(SubjectKind.LEGAL_ENTITY);

    private final SubjectKind subject;

    DocumentKind(SubjectKind subject) {
        this.subject = subject;
    }

    /** Who may submit this. */
    public SubjectKind subject() {
        return subject;
    }

    /** Whether this kind belongs to that subject. */
    public boolean isFor(SubjectKind kind) {
        return subject == kind;
    }

    /** What a subject of this kind may submit. */
    public static Set<DocumentKind> forSubject(SubjectKind kind) {
        return Set.of(values()).stream()
                .filter(document -> document.isFor(kind))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The kind a client named, or empty when it named something else. */
    public static Optional<DocumentKind> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
