package az.ideanest.verification.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Who is being verified — §4.2's "individual or legal entity", issue #105.
 *
 * <p>It decides what may be submitted, and nothing else. {@code DocumentKind} carries the
 * mapping, so that "a company cannot submit a passport as its registration" is one place
 * rather than a check in the controller and another in the service.
 */
public enum SubjectKind {

    /** A person. Shows an identity document. */
    INDIVIDUAL,

    /**
     * A company.
     *
     * <p>Shows a registration extract. §4.2 has no company entity yet — a creator is a
     * {@code users} row whichever they are — so this is a property of the verification
     * rather than of the account, and it is asked at submission.
     */
    LEGAL_ENTITY;

    /** The kind a client named, or empty when it named something else. */
    public static Optional<SubjectKind> parse(String value) {
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
