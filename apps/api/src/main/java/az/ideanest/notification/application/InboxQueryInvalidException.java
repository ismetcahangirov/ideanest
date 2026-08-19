package az.ideanest.notification.application;

import java.util.Map;
import java.util.Objects;

/**
 * The inbox was asked for in a way it cannot be read.
 *
 * <p>A 400 and not a 422: what is wrong is a query parameter rather than the state of
 * anything stored. {@code CommentContentInvalidException} is the same shape one module
 * over — the field travels with the message so the client can point at the parameter it
 * got wrong instead of bisecting its own request.
 *
 * <p>Two things raise it, and they are one exception rather than two because they are one
 * mistake from the client's side — a request that has to be corrected before it can be
 * sent again — and because the {@code meta} carries which:
 *
 * <ul>
 *   <li>Half a cursor. {@code before} and {@code beforeId} are the two halves of one
 *       position and neither is usable alone.
 *   <li>A page size outside the configured bounds. Refused rather than clamped: a client
 *       that asked for a thousand and silently received a hundred goes on believing it
 *       read the whole inbox, and the bug surfaces as notifications nobody ever saw. The
 *       ceiling is in the {@code meta} so the fix does not require reading this source.
 * </ul>
 */
public class InboxQueryInvalidException extends RuntimeException {

    private final String field;

    private final transient Map<String, Object> meta;

    public InboxQueryInvalidException(String message, String field, Map<String, Object> meta) {
        super(message);
        this.field = Objects.requireNonNull(field, "A refusal names the parameter it is about");
        this.meta = Map.copyOf(Objects.requireNonNull(meta, "A refusal carries some metadata, possibly none"));
    }

    /** The query parameter to put the message beside. */
    public String field() {
        return field;
    }

    /** Whatever a client needs to correct the request — a bound, usually. */
    public Map<String, Object> meta() {
        return meta;
    }
}
