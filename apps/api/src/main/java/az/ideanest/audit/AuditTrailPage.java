package az.ideanest.audit;

import java.util.List;
import java.util.UUID;

/**
 * One page of the trail, newest first.
 *
 * <p><strong>Newest first, where the report queue is oldest first, and the difference is
 * not a preference.</strong> A queue is worked from the front: the report that has waited
 * longest is the one that matters, so it is shown first. A trail is read from the end —
 * "what has just happened", "what did that account do before it was stopped" — and the row
 * somebody wants is almost always the most recent one that matches.
 *
 * <p><strong>A cursor and no total</strong>, for {@code ReportQueuePage}'s reason. Counting
 * a table nothing is ever deleted from is a full scan for a number that is stale before it
 * renders, and the only decision made from it — is there more — is answered exactly by
 * whether {@link #nextCursor()} is present.
 *
 * @param filter what was asked for, carried back so a client with two filters open can tell
 *     which response belongs to which
 * @param entries the matching rows, newest first
 * @param nextCursor where the last row on this page sits, to send as {@code after} for the
 *     next one, or null when this was the last page. Both an instant and an identifier
 *     since #404: the trail is ordered by {@code occurred_at}, which is the column the
 *     screen displays and is not unique, so the key breaks the tie. This used to be the
 *     identifier alone, on the argument that a UUID v7 is a position in arrival order
 *     (§7.3) — {@link AuditCursor} records what was wrong with that
 */
public record AuditTrailPage(AuditTrailFilter filter, List<AuditEntry> entries, AuditCursor nextCursor) {

    public AuditTrailPage {
        entries = List.copyOf(entries);
    }
}
