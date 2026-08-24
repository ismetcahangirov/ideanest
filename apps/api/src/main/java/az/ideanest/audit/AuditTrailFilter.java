package az.ideanest.audit;

import java.util.UUID;

/**
 * Which of the four questions the trail is being asked — AD-14, #314.
 *
 * <p><strong>Four shapes rather than a bag of optional predicates, because the table has
 * four indexes and no others.</strong> V21 gives {@code audit_logs} an index on
 * {@code (occurred_at DESC)}, one on {@code (actor_id, occurred_at DESC)}, one on
 * {@code (entity_type, entity_id, occurred_at DESC)} and a partial one on
 * {@code on_behalf_of_id}. A filter that is not one of those is a sequential scan over the
 * one table on the platform that only ever grows and is never pruned — and the first person
 * to run it is a moderator on a Tuesday, not a load test.
 *
 * <p>So the vocabulary is deliberately small, and what is absent is absent on purpose:
 *
 * <ul>
 *   <li><strong>No filter on {@code action}.</strong> "Every refund ever recorded" is a
 *       reasonable question and there is no index that answers it. It is a one-line
 *       migration to add one the day somebody needs it, and adding the parameter first
 *       would mean shipping the scan and discovering it in production.
 *   <li><strong>No date range.</strong> The order <em>is</em> the date — the identifier
 *       carries the millisecond — so "since Tuesday" is reading pages until the dates stop
 *       being interesting, which is what a reader does anyway.
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
 */
public record AuditTrailFilter(String entityType, UUID entityId, UUID actorId) {

    /** The whole table, newest first. */
    public static final AuditTrailFilter EVERYTHING = new AuditTrailFilter(null, null, null);

    /**
     * The filter this actually is, with the combinations no index serves resolved.
     *
     * <p>Resolved here rather than refused with a 400. A client that sends both an actor and
     * an entity has asked a question this release cannot answer efficiently; answering the
     * narrower half of it — the entity — and saying so in the echoed filter is more useful
     * than a refusal, and it is visible, which a silent drop would not be.
     */
    public AuditTrailFilter normalised() {
        String type = blankToNull(entityType);
        if (type != null) {
            return new AuditTrailFilter(type, entityId, null);
        }
        // An entity id with no kind cannot use the index it belongs to; see the record's note.
        return new AuditTrailFilter(null, null, actorId);
    }

    /** Whether this filter narrows to one thing rather than to a kind of thing. */
    public boolean isSingleEntity() {
        return entityType != null && entityId != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
