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
 * thread saying nothing — both would satisfy a bare "not null" and neither is
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
        if (value == null || value.isBlank()) {
            throw new CommentContentInvalidException("body", "A comment needs something to say.");
        }
        String trimmed = value.strip();
        // Code points rather than String.length, for UpdateContent's reason: this has
        // to agree with PostgreSQL's char_length and with the counter the client
        // shows, or the number on screen is a lie about which one refuses first.
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            throw new CommentContentInvalidException(
                    "body", "A comment holds at most " + MAX_LENGTH + " characters.");
        }
        return trimmed;
    }
}
