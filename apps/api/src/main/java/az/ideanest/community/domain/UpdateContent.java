package az.ideanest.community.domain;

/**
 * What an update's title and body may be.
 *
 * <p>A pure static type with no Spring and no database in sight, for the reason
 * {@code StoryDocuments} is one: a table of rules is worth testing on its own rather
 * than through an HTTP request that also had to authenticate, authorise, and open a
 * transaction.
 *
 * <p><strong>The same bounds are check constraints in V22, and that is not
 * duplication.</strong> This one catches the creator, with a message naming the field
 * they can fix; the constraint catches a bulk import, a support query, and the next
 * write path somebody adds. An application rule is enforced by whichever code
 * remembered to call it.
 *
 * <p><strong>Trimmed before it is measured and before it is stored.</strong> A title
 * of eighty spaces is not a title, and an update body that is one newline is a
 * notification sent about nothing — both would satisfy a bare "not null" and neither
 * is something to publish to people who paid money.
 */
public final class UpdateContent {

    /**
     * §4.4's Updates tab lists these, and §4.10's "new update published" makes each
     * one a subject line. A hundred and twenty characters is long enough for a
     * sentence and short enough to be one.
     */
    public static final int MAX_TITLE_LENGTH = 120;

    /**
     * A ceiling on one row rather than an editorial opinion. Forty thousand
     * characters is longer than any update anybody reads to the end, and it is what
     * stops one post from being made expensive to store, serve, and render.
     */
    public static final int MAX_BODY_LENGTH = 40_000;

    private UpdateContent() {
    }

    /**
     * The title as it will be stored.
     *
     * @throws UpdateContentInvalidException when it is blank or too long
     */
    public static String title(String value) {
        return required(value, "title", MAX_TITLE_LENGTH, "An update needs a title.");
    }

    /**
     * The body as it will be stored.
     *
     * @throws UpdateContentInvalidException when it is blank or too long
     */
    public static String body(String value) {
        return required(value, "body", MAX_BODY_LENGTH, "An update needs something to say.");
    }

    private static String required(String value, String field, int maxLength, String whenMissing) {
        if (value == null || value.isBlank()) {
            throw new UpdateContentInvalidException(field, whenMissing);
        }
        String trimmed = value.strip();
        // Code points rather than String.length, so this agrees with PostgreSQL's
        // char_length and with the counter the editor shows. An emoji is one
        // character in all three or the number on screen is a lie.
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new UpdateContentInvalidException(
                    field, "An update's " + field + " holds at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
