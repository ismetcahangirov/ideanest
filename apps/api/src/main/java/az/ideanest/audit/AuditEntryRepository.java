package az.ideanest.audit;

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
 * <h2>Ordered by identifier, not by {@code occurred_at}</h2>
 *
 * <p>Both columns say the same thing — the identifier is a UUID v7 and carries the
 * millisecond it was minted in (§7.3) — and only one of them is unique. Ordering by the
 * timestamp would need a compound cursor to break ties, and two rows written by the same
 * transaction share a timestamp often enough that the tie is the normal case rather than
 * the edge one. Ordering by the primary key gives the same sequence with a cursor that is
 * one value.
 *
 * <p>The cost is stated rather than hidden: V21's indexes all end in
 * {@code occurred_at DESC}, so PostgreSQL reads the index for the predicate and sorts the
 * matching rows by {@code id}. On a filtered read that set is small. On the unfiltered
 * read it is the primary key's own order, which needs no sort at all.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    /** What has been done to one thing, most recent first. */
    List<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId);

    /** What one account has done, most recent first. */
    List<AuditEntry> findByActorIdOrderByOccurredAtDesc(UUID actorId);

    /** The newest rows in the table. AD-14's default view: what has just happened. */
    @Query("SELECT e FROM AuditEntry e ORDER BY e.id DESC")
    List<AuditEntry> newest(Pageable limit);

    /** The page after {@code before}. Keyset, so a row written mid-read shifts nothing. */
    @Query("SELECT e FROM AuditEntry e WHERE e.id < :before ORDER BY e.id DESC")
    List<AuditEntry> newestBefore(@Param("before") UUID before, Pageable limit);

    /** Everything that has happened to one kind of thing. */
    @Query("SELECT e FROM AuditEntry e WHERE e.entityType = :entityType ORDER BY e.id DESC")
    List<AuditEntry> newestOfType(@Param("entityType") String entityType, Pageable limit);

    /** The page after {@code before}, within one kind of thing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.entityType = :entityType AND e.id < :before
            ORDER BY e.id DESC
            """)
    List<AuditEntry> newestOfTypeBefore(
            @Param("entityType") String entityType, @Param("before") UUID before, Pageable limit);

    /** Everything that has happened to one thing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.entityType = :entityType AND e.entityId = :entityId
            ORDER BY e.id DESC
            """)
    List<AuditEntry> newestOfEntity(
            @Param("entityType") String entityType, @Param("entityId") UUID entityId, Pageable limit);

    /** The page after {@code before}, within one thing. */
    @Query(
            """
            SELECT e FROM AuditEntry e
            WHERE e.entityType = :entityType AND e.entityId = :entityId AND e.id < :before
            ORDER BY e.id DESC
            """)
    List<AuditEntry> newestOfEntityBefore(
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("before") UUID before,
            Pageable limit);

    /** Everything one account has done. */
    @Query("SELECT e FROM AuditEntry e WHERE e.actorId = :actorId ORDER BY e.id DESC")
    List<AuditEntry> newestByActor(@Param("actorId") UUID actorId, Pageable limit);

    /** The page after {@code before}, within one account's actions. */
    @Query("SELECT e FROM AuditEntry e WHERE e.actorId = :actorId AND e.id < :before ORDER BY e.id DESC")
    List<AuditEntry> newestByActorBefore(
            @Param("actorId") UUID actorId, @Param("before") UUID before, Pageable limit);
}
