package az.ideanest.community.domain;

/**
 * What a comment may say.
 *
 * <p>A pure static type with no Spring and no database in sight, for the reason
 * {@code UpdateContent} is one: a table of rules is worth testing on its own rather
 * than through an HTTP request that also had to authenticate, authorise, and open a
 * transaction.
 *
 * <p><strong>The same bounds are check constraints in V25, and that is not
 * duplication.</strong> This one catches the person typing, with a message they can
 * act on; the constraint catches a bulk import, a support query, and the next write
 * path somebody adds. An application rule is enforced by whichever code remembered to
 * call it.
 *
 * <p><strong>Trimmed before it is measured and before it is stored.</strong> A comment
 * of eighty spaces is not a comment, and one that is a single newline is a row in a
 * thread saying nothing - both would satisfy a bare "not null" and neither is
 * something to put on a public page under somebody's campaign.
 */
public final class CommentBody {

    /**
     * A ceiling on one row rather than an editorial opinion.
     *
     * <p>Far shorter than {@code UpdateContent.MAX_BODY_LENGTH}, deliberately: an
     * update is a creator writing to people who paid them and is read to the end, and
     * a comment is one turn in a conversation on a page that may hold forty thousand
     * of them. Five thousand characters is longer than anybody writes in a comment box
     * and short enough that a hot thread stays a page rather than a download.
     */
    public static final int MAX_LENGTH = 5_000;

    private CommentBody() {
    }

    /**
     * The comment as it will be stored.
     *
     * @throws CommentContentInvalidException when it is blank or too long
     */
    public static String of(String value) {
        if (value == null) {
            throw new CommentContentInvalidException("body", "A comment needs something to say.");
        }
        String trimmed = strip(value);
        if (trimmed.isEmpty()) {
            throw new CommentContentInvalidException("body", "A comment needs something to say.");
        }
        // Code points rather than String.length, for UpdateContent's reason: this has
        // to agree with PostgreSQL's char_length and with the counter the client
        // shows, or the number on screen is a lie about which one refuses first.
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            throw new CommentContentInvalidException(
                    "body", "A comment holds at most " + MAX_LENGTH + " characters.");
        }
        return trimmed;
    }

    /**
     * Trims what a reader would see as nothing, which is more than
     * {@link String#strip()} removes.
     *
     * <p><strong>{@code String.isBlank} and {@code String.strip} both stop at
     * {@link Character#isWhitespace}, and U+00A0 NO-BREAK SPACE is deliberately not
     * whitespace to that method.</strong> So a comment of one non-breaking space --
     * option-space on a Mac, and one character anybody can paste -- passes
     * {@code isBlank} as though it had said something, and is stored as a row that
     * renders as nothing at all under somebody's campaign.
     * {@link Character#isSpaceChar} is the other half of the question, and the two
     * predicates together are "would a reader see anything here". U+2007 and U+202F
     * fall in the same gap.
     *
     * <p><strong>This is stricter than V25's {@code !~ '^\s*$'}, and that direction is
     * the safe one.</strong> The constraint is the backstop for a write path that never
     * called this method; a rule the application applies more tightly than the database
     * refuses nothing the database would have stored badly. The reverse - a constraint
     * stricter than the type - is a 500 where the honest answer names the field.
     *
     * <p>Iterating by {@code char} rather than by code point is safe and stays so:
     * every space character in Unicode is in the basic multilingual plane, and neither
     * predicate answers true for a surrogate, so an emoji at either end is left alone.
     */
    private static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
