package az.ideanest.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Audit rows.
 *
 * <p><strong>Read and insert, and the interface says so.</strong>
 * {@link JpaRepository} brings {@code delete}, {@code deleteAll} and
 * {@code deleteAllInBatch} with it, and every one of them is a statement V21's
 * trigger refuses — so calling one is a runtime failure rather than a compile
 * failure, which is the one thing about this file worth knowing. Narrowing to
 * {@code Repository} and declaring five methods by hand would fix that and cost the
 * paging, sorting and flushing the writing side actually uses; the enforcement that
 * matters is in the database, and it does not care which interface asked.
 *
 * <p>The two unpaged finders are the two questions §7.2 says this table exists to
 * answer. The paged ones below them are the same questions asked by AD-14's viewer
 * (#314), which cannot read an unbounded list into a browser; every one of the four
 * shapes is index-backed by V21, and {@link az.ideanest.audit.AuditTrailFilter} explains
 * why there are four and not an arbitrary combination.
 *
 * <h2>Ordered by {@code occurred_at}, and it used to be by the identifier</h2>
 *
 * <p>The old argument was that both columns say the same thing — the identifier is a UUID
 * v7 and carries the millisecond it was minted in (§7.3) — and that only one of them is
 * unique, so ordering by the primary key gave the same sequence with a cursor that was one
 * value. The two columns are written by two different clocks, and #404 is what that cost:
 * the identifier is minted in the application when the row is built, {@code occurred_at} is
 * {@code DEFAULT now()} and is taken when the insert lands, and the viewer displays the
 * second while the query ordered by the first. A page headed "newest first" opened on last
 * month.
 *
 * <p>So every query below orders by {@code (occurred_at DESC, id DESC)} — the column the
 * screen shows, with the key breaking the tie — and the keyset predicate is the row-value
 * comparison that pair implies, written out rather than as a tuple because JPQL has no
 * row constructor. Two rows written by one transaction share an instant often enough that
 * the tie is the normal case here rather than the edge one, which is why the cursor carries
 * both halves. {@link AuditCursor} holds the whole argument.
 *
 * <p>The cost is stated rather than hidden, as it was before: V21's indexes all end in
 * {@code occurred_at DESC}, so this is now the index's own order on the filtered reads and
 * the tiebreak applies to the handful of rows sharing one timestamp. The unfiltered read
 * walks {@code audit_logs_occurred_at_idx} instead of the primary key. Adding {@code id} to
 * each index would make the keyset exact rather than nearly exact, and is an index rebuild
 * on the one table that only ever grows — worth doing on the day the tie is measurably
 * expensive, and not before.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    /** What has been done to one thing, most recent first. */
    List<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId);

    /** What one account has done, most recent first. */
    List<AuditEntry> findByActorIdOrderByOccurredAtDesc(UUID actorId);

    /** The newest rows in the table. AD-14's default view: what has just happened. */
    @Query("SELECT e FROM AuditEntry e ORDER BY e.occurredAt DESC, e.id DESC")
    List<AuditEntry> newest(Pageable limit);

    /** The page after the row at {@code (before, beforeId)}. Keyset: a row written mid-read shifts nothing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.occurredAt < :before OR (e.occurredAt = :before AND e.id < :beforeId)
            ORDER BY e.occurredAt DESC, e.id DESC
            """)
    List<AuditEntry> newestBefore(
            @Param("before") Instant before, @Param("beforeId") UUID beforeId, Pageable limit);

    /** Everything that has happened to one kind of thing. */
    @Query("SELECT e FROM AuditEntry e WHERE e.entityType = :entityType ORDER BY e.occurredAt DESC, e.id DESC")
    List<AuditEntry> newestOfType(@Param("entityType") String entityType, Pageable limit);

    /** The page after that row, within one kind of thing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.entityType = :entityType
              AND (e.occurredAt < :before OR (e.occurredAt = :before AND e.id < :beforeId))
            ORDER BY e.occurredAt DESC, e.id DESC
            """)
    List<AuditEntry> newestOfTypeBefore(
            @Param("entityType") String entityType,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable limit);

    /** Everything that has happened to one thing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.entityType = :entityType AND e.entityId = :entityId
            ORDER BY e.occurredAt DESC, e.id DESC
            """)
    List<AuditEntry> newestOfEntity(
            @Param("entityType") String entityType, @Param("entityId") UUID entityId, Pageable limit);

    /** The page after that row, within one thing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.entityType = :entityType AND e.entityId = :entityId
              AND (e.occurredAt < :before OR (e.occurredAt = :before AND e.id < :beforeId))
            ORDER BY e.occurredAt DESC, e.id DESC
            """)
    List<AuditEntry> newestOfEntityBefore(
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable limit);

    /** Everything one account has done. */
    @Query("SELECT e FROM AuditEntry e WHERE e.actorId = :actorId ORDER BY e.occurredAt DESC, e.id DESC")
    List<AuditEntry> newestByActor(@Param("actorId") UUID actorId, Pageable limit);

    /** The page after that row, within one account's actions. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.actorId = :actorId
              AND (e.occurredAt < :before OR (e.occurredAt = :before AND e.id < :beforeId))
            ORDER BY e.occurredAt DESC, e.id DESC
            """)
    List<AuditEntry> newestByActorBefore(
            @Param("actorId") UUID actorId,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable limit);
}
