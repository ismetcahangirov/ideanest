package az.ideanest.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reading side of the trail — AD-14, #314.
 *
 * <p>{@link AuditLog} writes and this reads, and they are two classes because they have
 * two different callers and two different rules. Every module in the service writes; one
 * screen reads. Putting the read on {@code AuditLog} would put a paged query in front of
 * every caller whose only business here is one {@code record} call, and the first
 * consequence of that is somebody reading the trail inside the transaction that is
 * writing to it.
 *
 * <p><strong>There is no authorisation here, and that is deliberate.</strong> This class
 * is inside the cross-cutting package everything may depend on, and a staff check in it
 * would either duplicate {@code shared.access.PlatformStaff} or make the audit package
 * depend on a module. The refusal lives one layer out, in
 * {@code admin.application.AuditTrailService}, which is also where reading the trail is
 * itself recorded — the two belong in the same place, for the reason
 * {@code UserAdministrationService} gives about a search.
 *
 * <p><strong>Read-only, and it could not be otherwise.</strong> V21 puts a trigger on the
 * table that raises on UPDATE, DELETE and TRUNCATE. Nothing here could edit a row if it
 * tried, which is the property that makes the trail worth reading at all.
 */
@Service
public class AuditTrail {

    private final AuditEntryRepository entries;

    public AuditTrail(AuditEntryRepository entries) {
        this.entries = entries;
    }

    /**
     * One page of the trail, newest first.
     *
     * @param filter one of the four shapes {@link AuditTrailFilter} allows. Normalised
     *     first, so the page and the echoed filter describe the same query
     * @param before the last row on the previous page, or null for the first page. A
     *     position rather than a row that has to exist: it names an instant and an
     *     identifier, and every row below that pair is still a correct answer even if the
     *     row it was taken from has been detached with its partition
     * @param limit already clamped by the caller, which is where a request's shape is
     *     decided
     */
    @Transactional(readOnly = true)
    public AuditTrailPage page(AuditTrailFilter filter, AuditCursor before, int limit) {
        AuditTrailFilter asked = filter.normalised();
        PageRequest page = PageRequest.ofSize(limit);
        List<AuditEntry> rows = rowsFor(asked, before, page);

        /*
         * A full page is the only honest signal that there may be more — the same rule
         * the report queue follows. Reporting "no more" on a page that happened to fill
         * exactly would hide the tail; reporting a cursor on a short page would cost the
         * client one request to learn the same thing.
         */
        AuditCursor nextCursor = rows.size() < limit ? null : cursorTo(rows.get(rows.size() - 1));
        return new AuditTrailPage(asked, rows, nextCursor);
    }

    /**
     * Where the page just served ends.
     *
     * <p>Both halves, because the trail is ordered by a column that is not unique — see
     * {@link AuditCursor}, which is where the whole of #404's ordering argument lives.
     */
    private static AuditCursor cursorTo(AuditEntry last) {
        return new AuditCursor(last.getOccurredAt(), last.getId());
    }

    /**
     * What has happened to one thing, most recent first and unpaged.
     *
     * <p>The detail read: a screen showing one campaign, one report or one account asks
     * for its history and gets all of it. Unpaged because the history of a single entity
     * is bounded by how many times somebody acted on it, which is tens rather than
     * thousands — unlike the trail as a whole, which is bounded by nothing.
     */
    @Transactional(readOnly = true)
    public List<AuditEntry> historyOf(String entityType, UUID entityId) {
        return entries.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId);
    }

    /**
     * The rows, from whichever of the eight queries this filter names.
     *
     * <p>The date range is not a branch — it is two parameters every one of the eight takes.
     * {@code occurred_at} is the trailing column of all four of V21's indexes, so a bound on
     * it narrows whichever scan the shape above already chose; {@code AuditEntryRepository}
     * carries the argument for why that makes it a parameter here and a separate query on the
     * payment log, and for why neither bound is ever null.
     */
    private List<AuditEntry> rowsFor(AuditTrailFilter filter, AuditCursor before, PageRequest page) {
        // The effective bounds, never null: `AuditTrailFilter.SINCE` and `UNTIL` stand in for
        // an absent one, because a nullable instant in the predicate is a parameter Hibernate
        // cannot type. The filter still echoes the nulls, so the response says "unbounded".
        Instant from = filter.effectiveFrom();
        Instant to = filter.effectiveTo();

        if (filter.isSingleEntity()) {
            return before == null
                    ? entries.newestOfEntity(filter.entityType(), filter.entityId(), from, to, page)
                    : entries.newestOfEntityBefore(
                            filter.entityType(), filter.entityId(), before.at(), before.id(), from, to, page);
        }
        if (filter.entityType() != null) {
            return before == null
                    ? entries.newestOfType(filter.entityType(), from, to, page)
                    : entries.newestOfTypeBefore(filter.entityType(), before.at(), before.id(), from, to, page);
        }
        if (filter.actorId() != null) {
            return before == null
                    ? entries.newestByActor(filter.actorId(), from, to, page)
                    : entries.newestByActorBefore(filter.actorId(), before.at(), before.id(), from, to, page);
        }
        return before == null
                ? entries.newest(from, to, page)
                : entries.newestBefore(before.at(), before.id(), from, to, page);
    }
}
