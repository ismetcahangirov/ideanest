package az.ideanest.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Which of the four questions the trail is being asked, and over which stretch of time —
 * AD-14, #314 and #404.
 *
 * <p><strong>Four shapes rather than a bag of optional predicates, because the table has
 * four indexes and no others.</strong> V21 gives {@code audit_logs} an index on
 * {@code (occurred_at DESC)}, one on {@code (actor_id, occurred_at DESC)}, one on
 * {@code (entity_type, entity_id, occurred_at DESC)} and a partial one on
 * {@code on_behalf_of_id}. A filter that is not one of those is a sequential scan over the
 * one table on the platform that only ever grows and is never pruned — and the first person
 * to run it is a moderator on a Tuesday, not a load test.
 *
 * <h2>The date range, which is the fifth thing and costs nothing</h2>
 *
 * <p>This record used to say there was no date range, on the argument that "the order
 * <em>is</em> the date — the identifier carries the millisecond — so 'since Tuesday' is
 * reading pages until the dates stop being interesting, which is what a reader does anyway".
 * Both halves of that turned out to be wrong.
 *
 * <p>The first half was the ordering defect #404 opened with: the identifier and
 * {@code occurred_at} are written by two different clocks, the trail is ordered by the second
 * now, and the first no longer stands in for the date at all. The second half is what an
 * operator actually does with an audit log — "what did this person do last Tuesday" is the
 * question the surface exists to answer, and answering it by paging through everything that
 * has happened since is not reading, it is scrolling.
 *
 * <p><strong>And unlike everything else here, it needs no index.</strong> Every one of V21's
 * four indexes ends in {@code occurred_at DESC}. A range over that column is the trailing
 * component of whichever index the shape already chose, so the range narrows the scan the
 * query was going to do rather than adding one. That is the difference between this filter
 * and the two below it, and it is the whole reason it is here and they are not.
 *
 * <p>So the vocabulary is still deliberately small, and what is absent is absent on purpose:
 *
 * <ul>
 *   <li><strong>No filter on {@code action}.</strong> "Every refund ever recorded" is a
 *       reasonable question and there is no index that answers it. It is a one-line
 *       migration to add one the day somebody needs it, and adding the parameter first
 *       would mean shipping the scan and discovering it in production.
 *   <li><strong>No free-text search over {@code detail}.</strong> That column holds prose
 *       written by the platform about people, in a table with no retention rule; making it
 *       searchable is a decision about personal data and not a feature.
 * </ul>
 *
 * @param entityType the kind of thing acted upon — {@code project}, {@code account},
 *     {@code report}, and the rest of {@link AuditAction#entityType()} — or null. On its own
 *     it reads the entity index by its leading column, which answers "everything that has
 *     happened to campaigns"
 * @param entityId one thing, and only meaningful with {@link #entityType()}: identifiers do
 *     not collide across kinds, but the index leads on the type and an entity id alone would
 *     not use it. {@link #normalised()} drops it rather than pretending
 * @param actorId one account, which answers "what did this member of staff do". Not combined
 *     with the entity filter, because no index serves both and choosing one silently would
 *     be answering a different question from the one asked
 * @param from the earliest instant to include, or null for the beginning of the table.
 *     Inclusive, because a reader who asks for Tuesday means from midnight
 * @param to the first instant to exclude, or null for the end. <strong>Exclusive</strong>,
 *     so that "Tuesday" is {@code [Tuesday 00:00, Wednesday 00:00)} and two adjacent days
 *     partition the rows between them instead of both claiming midnight. A caller that
 *     computes the second bound from a chosen day never has to think about a boundary row
 */
public record AuditTrailFilter(String entityType, UUID entityId, UUID actorId, Instant from, Instant to) {

    /** The whole table, newest first. */
    public static final AuditTrailFilter EVERYTHING = new AuditTrailFilter(null, null, null, null, null);

    /**
     * The lower end of the column's useful range, for a read with no {@link #from()}.
     *
     * <p><strong>A bound that excludes nothing, rather than no bound at all.</strong>
     * {@code AuditEntryRepository} carries the argument: a {@code :from IS NULL} predicate
     * leaves Hibernate a parameter with no type to infer, and the whole of AD-14 answered 500.
     * So the queries always compare, and "unbounded" is expressed here.
     *
     * <p>The epoch and not {@code Instant.MIN}: {@code MIN} is a billion years before the
     * lower limit of a {@code timestamptz}, and the platform's first audit row was written in
     * 2026. A bound older than the table can hold selects exactly what no bound would.
     */
    public static final Instant SINCE = Instant.EPOCH;

    /**
     * The upper end, for a read with no {@link #to()}.
     *
     * <p>Inside what {@code timestamptz} accepts — its ceiling is far higher — and far beyond
     * anything a clock on this platform will produce. {@link #to()} is exclusive everywhere
     * else, and this is chosen so that staying consistent about that costs nothing.
     */
    public static final Instant UNTIL = Instant.parse("9999-12-31T23:59:59Z");

    /** {@link #from()}, or the bound that stands in for having none. */
    public Instant effectiveFrom() {
        return from == null ? SINCE : from;
    }

    /** {@link #to()}, or the bound that stands in for having none. */
    public Instant effectiveTo() {
        return to == null ? UNTIL : to;
    }

    /**
     * The filter this actually is, with the combinations no index serves resolved.
     *
     * <p>Resolved here rather than refused with a 400. A client that sends both an actor and
     * an entity has asked a question this release cannot answer efficiently; answering the
     * narrower half of it — the entity — and saying so in the echoed filter is more useful
     * than a refusal, and it is visible, which a silent drop would not be.
     *
     * <p><strong>The range survives every branch</strong>, because it composes with all four
     * shapes rather than competing with them: it is the trailing column of each of the four
     * indexes. An inverted range — {@code to} at or before {@code from} — is left exactly as
     * it was sent and matches nothing, which is the honest answer to a question that asks
     * for the rows between Friday and Tuesday.
     */
    public AuditTrailFilter normalised() {
        String type = blankToNull(entityType);
        if (type != null) {
            return new AuditTrailFilter(type, entityId, null, from, to);
        }
        // An entity id with no kind cannot use the index it belongs to; see the record's note.
        return new AuditTrailFilter(null, null, actorId, from, to);
    }

    /** Whether this filter narrows to one thing rather than to a kind of thing. */
    public boolean isSingleEntity() {
        return entityType != null && entityId != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
