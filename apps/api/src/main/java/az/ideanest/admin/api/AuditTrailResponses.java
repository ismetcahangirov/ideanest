package az.ideanest.admin.api;

import az.ideanest.audit.AuditEntry;
import az.ideanest.audit.AuditTrailPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AD-14's trail on the wire — #314.
 *
 * <p>Every field of {@code audit_logs} is here except the primary key's neighbours that
 * mean nothing outside the database. That is the opposite of the choice
 * {@code LoggedTransaction} makes about a provider's raw response, and the difference is
 * what the two tables are for: a payment row is evidence about a person's money and is read
 * for one fact at a time, and an audit row <em>is</em> the disclosure — withholding half of
 * it would leave an investigator reading the database directly, which is the outcome this
 * screen exists to avoid.
 *
 * <p>{@code sourceAddress} and {@code userAgent} are on the wire, and §17.4's redaction has
 * already been applied to them on the way in — see the audit package's own note. An address
 * that reached the column is one the platform decided to keep, and a viewer that hid it
 * would be hiding it from the only people entitled to see it.
 */
final class AuditTrailResponses {

    private AuditTrailResponses() {
    }

    static Page of(AuditTrailPage page) {
        return new Page(
                page.filter().entityType(),
                page.filter().entityId(),
                page.filter().actorId(),
                page.entries().stream().map(AuditTrailResponses::entry).toList(),
                page.nextCursor());
    }

    private static Entry entry(AuditEntry row) {
        return new Entry(
                row.getId(),
                row.getOccurredAt(),
                row.getActorType().name(),
                row.getActorId(),
                row.getOnBehalfOfId(),
                row.getAction(),
                row.getEntityType(),
                row.getEntityId(),
                row.getOutcome().name(),
                row.getSourceAddress(),
                row.getUserAgent(),
                row.getRequestId(),
                row.getTraceId(),
                row.getDetail());
    }

    /**
     * One page of the trail, newest first.
     *
     * @param entityType which kind of thing was asked about, echoed and absent when the
     *     request asked about every kind. Echoed rather than assumed because the service
     *     normalises a filter it has no index for — see {@code AuditTrailFilter} — and a
     *     client has no other way to learn that the question it asked was narrowed
     * @param entityId which thing, echoed for the same reason
     * @param actorId which account's actions, echoed for the same reason
     * @param entries the matching rows, newest first
     * @param nextCursor what to send as {@code after} for the next page, or absent when
     *     this was the last one. There is no total: counting a table nothing is ever
     *     deleted from is a scan for a number that is stale before it renders
     */
    record Page(
            String entityType,
            UUID entityId,
            UUID actorId,
            List<Entry> entries,
            UUID nextCursor) {
    }

    /**
     * One privileged action.
     *
     * @param id the row, which is a UUID v7 and therefore its own position in the trail
     * @param occurredAt from the database's clock, not the application's
     * @param actorType {@code USER}, {@code MODERATOR} or {@code SYSTEM} — what the actor
     *     was acting as, which is not the same question as who they were
     * @param actorId the account, or absent when the actor was the platform itself
     * @param onBehalfOfId whom the action was taken for, when it was taken by somebody
     *     else. Absent on almost every row, and the reason the column exists is AD-04's
     *     impersonation (#299), which is unbuilt and blocked on a policy question
     * @param action the spelling from {@code AuditAction}, which is a closed set on the
     *     writing side and open text in the column — so a client filtering on
     *     {@code project.approved} is not silently missing rows spelled another way
     * @param entityType what kind of thing was acted upon
     * @param entityId which one
     * @param outcome {@code SUCCEEDED} or {@code REFUSED}. A refusal is a row: the attempt
     *     is what happened, and a trail holding only the successes is a trail that cannot
     *     show somebody trying
     * @param sourceAddress where the request came from, redacted on the way in
     * @param userAgent what made it, redacted on the way in
     * @param requestId the correlation identifier this row shares with §18.1's log lines
     * @param traceId the same, for the tracing backend
     * @param detail what the action recorded about itself, in the vocabulary of whatever
     *     wrote it. Prose, and only ever rendered as text
     */
    record Entry(
            UUID id,
            Instant occurredAt,
            String actorType,
            UUID actorId,
            UUID onBehalfOfId,
            String action,
            String entityType,
            UUID entityId,
            String outcome,
            String sourceAddress,
            String userAgent,
            String requestId,
            String traceId,
            String detail) {
    }
}
