package az.ideanest.discovery.domain;

/**
 * A cursor that cannot be used for the request that carried it.
 *
 * <p>Two cases, and they are told apart by {@link #isMismatch()} because a client
 * fixes them differently. A cursor that does not decode is corrupt or forged, and
 * the client should start again from the first page. A cursor that decodes but was
 * minted against a different query is a client bug — it changed a filter or the
 * sort and kept paging — and the fix is to drop the cursor when the query changes.
 *
 * <p><strong>Both are refused rather than tolerated.</strong> Replaying a
 * {@code newest} cursor against {@code most_funded} would compare a timestamp with
 * an amount; there is no sensible answer, and the tempting one — ignore the cursor
 * and return page one — silently restarts an infinite scroll from the top, which
 * the client reads as "the feed has more items" and appends. The user then sees the
 * same cards twice and no error anywhere.
 *
 * <p>In {@code domain} rather than {@code application} because it is a rule of the
 * cursor itself, like {@code project.domain.StoryDocumentInvalidException}.
 */
public class InvalidCursorException extends RuntimeException {

    private final boolean mismatch;

    private InvalidCursorException(String message, boolean mismatch) {
        super(message);
        this.mismatch = mismatch;
    }

    /** The cursor is not a cursor: wrong version, wrong shape, or not decodable. */
    public static InvalidCursorException undecodable(String detail) {
        return new InvalidCursorException(detail, false);
    }

    /** The cursor is well formed and belongs to a different query or sort. */
    public static InvalidCursorException mismatched() {
        return new InvalidCursorException(
                "This cursor was issued for a different query. Drop the cursor when the filters or the sort change.",
                true);
    }

    public boolean isMismatch() {
        return mismatch;
    }
}
