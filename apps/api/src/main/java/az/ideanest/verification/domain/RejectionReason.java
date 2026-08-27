package az.ideanest.verification.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Why a submission was refused — issue #105.
 *
 * <h2>A closed set and not free text</h2>
 *
 * <p>The reason is shown to the creator, so it has to be one the product has written words
 * for in each of §21.1's languages — a free-text field is untranslatable by construction.
 *
 * <p>It is also the field where somebody would eventually paste what they saw on the
 * document. "The date of birth on the passport is 1984-03-02 and the account says 1985" is
 * a sentence a reviewer would type, and it would land in a column with no retention rule,
 * readable by everyone who can read the queue.
 */
public enum RejectionReason {

    /** The photograph cannot be read. Resubmittable, and the most common by far. */
    UNREADABLE,

    /** The document itself has expired. */
    EXPIRED_DOCUMENT,

    /** The name on the document is not the name on the account. */
    MISMATCHED_NAME,

    /** Something required was not submitted — a second side, or a registration extract. */
    INCOMPLETE,

    /**
     * The reviewer believes the document is not genuine.
     *
     * <p><strong>This one is not a verification outcome so much as the start of a different
     * conversation.</strong> Nothing here suspends the account: that is #103's, it is a
     * decision with an appeal behind it, and a state machine that suspended people
     * automatically would be one nobody could argue with.
     */
    SUSPECTED_FORGERY;

    public static Optional<RejectionReason> parse(String value) {
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
