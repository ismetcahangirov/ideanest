package az.ideanest.pledgemanager.domain;

/**
 * §4.8's PM-03: the shapes an answer can take.
 *
 * <p>Five, and no sixth without a migration — V35's check constraint holds the same
 * list. The names are the wire format, as they are on every enum the API exposes:
 * renaming one is a breaking change to every client and to every stored row.
 *
 * <p><strong>The list is short on purpose.</strong> A survey builder that offers
 * fifteen question types is a builder in which creators choose the wrong one, and
 * every type is something the platform then has to render, validate, and export. Each
 * of these earns its place from what a creator actually has to ask before they can
 * manufacture: a size, a colour, a list of extras, a delivery date, and where it goes.
 */
public enum QuestionType {

    /** One line or a paragraph. The answer is what they typed. */
    TEXT,

    /** Exactly one of the question's options. */
    CHOICE,

    /** One or more of them. */
    MULTI_CHOICE,

    /** An ISO 8601 calendar date. A date rather than an instant: nobody promises an hour. */
    DATE,

    /**
     * A postal address — and the one type that stores no answer.
     *
     * <p>The answer is the pledge's row in {@code shipping_addresses}, which #75
     * built: encrypted at rest, validated, and lockable by the creator. Copying it
     * into {@code survey_answers} would give the platform two addresses per backer
     * that can disagree, in a table with none of that machinery, and would put a home
     * address somewhere §17.4's erasure does not know to look.
     *
     * <p>So an ADDRESS question is a <em>prompt</em>: it tells the backer the creator
     * needs one and points the form at the address endpoint. V35 refuses to mark it
     * required for the same reason — whether an address is needed is decided by the
     * reward tier's shipping type, which is a fact about what was promised rather than
     * about how the survey was drawn.
     */
    ADDRESS;

    /** Whether the question carries a list of options the backer picks from. */
    public boolean hasChoices() {
        return this == CHOICE || this == MULTI_CHOICE;
    }

    /** Whether an answer to this is a row in {@code survey_answers}. See {@link #ADDRESS}. */
    public boolean storesAnswer() {
        return this != ADDRESS;
    }

    /** Whether more than one value is a legitimate answer. */
    public boolean acceptsMany() {
        return this == MULTI_CHOICE;
    }
}
