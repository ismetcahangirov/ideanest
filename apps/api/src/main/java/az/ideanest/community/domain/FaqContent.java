package az.ideanest.community.domain;

/**
 * What an FAQ entry's question and answer may be.
 *
 * <p>A pure static type with no Spring and no database in sight, for the reason
 * {@code UpdateContent} is one: a table of rules is worth testing on its own rather
 * than through an HTTP request that also had to authenticate, authorise, and open a
 * transaction.
 *
 * <p><strong>One statement of the rules, called by the entity rather than by the
 * edge.</strong> {@code ProjectFaq} runs every question and answer through here on the
 * way in — on creation and on edit alike — so there is no way to construct or change an
 * entry that the rules have not seen. The request records carry no bean validation for
 * exactly that reason: two statements of one rule are two messages and a race to see
 * which fires first.
 *
 * <p><strong>The same bounds are check constraints in V47, and that is not
 * duplication.</strong> This one catches the creator, with a message naming the field
 * they can fix; the constraint catches a bulk import, a support query, and the next
 * write path somebody adds. An application rule is enforced by whichever code
 * remembered to call it.
 *
 * <p><strong>Trimmed before it is measured and before it is stored.</strong> A question
 * of eighty spaces is not a question, and an answer that is one newline is an entry
 * that asks something on a public page and does not answer it — both would satisfy a
 * bare "not null" and neither is something to publish beside a request for money.
 */
public final class FaqContent {

    /**
     * A question is one sentence somebody would have typed into a support form. Long
     * enough for "will you ship to Georgia before the new year, and how much does that
     * cost", short enough that the tab is a list of questions rather than a list of
     * paragraphs.
     */
    public static final int MAX_QUESTION_LENGTH = 200;

    /**
     * A bound on one row rather than an editorial opinion. Four thousand characters is
     * longer than any answer anybody reads to the end, and — because the FAQ read is
     * deliberately not paged — it is also half of what bounds the whole response.
     */
    public static final int MAX_ANSWER_LENGTH = 4_000;

    private FaqContent() {
    }

    /**
     * The question as it will be stored.
     *
     * @throws FaqContentInvalidException when it is blank or too long
     */
    public static String question(String value) {
        return required(value, "question", MAX_QUESTION_LENGTH, "An FAQ entry needs a question.");
    }

    /**
     * The answer as it will be stored.
     *
     * @throws FaqContentInvalidException when it is blank or too long
     */
    public static String answer(String value) {
        return required(value, "answer", MAX_ANSWER_LENGTH, "An FAQ entry needs an answer.");
    }

    private static String required(String value, String field, int maxLength, String whenMissing) {
        if (value == null || value.isBlank()) {
            throw new FaqContentInvalidException(field, whenMissing);
        }
        String trimmed = value.strip();
        // Code points rather than String.length, so this agrees with PostgreSQL's
        // char_length and with the counter the editor shows. An emoji is one character
        // in all three or the number on screen is a lie.
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new FaqContentInvalidException(
                    field, "An FAQ entry's " + field + " holds at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
