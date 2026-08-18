package az.ideanest.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
 * <p>The two finders are the two questions §7.2 says this table exists to answer,
 * and both are index-backed by V21. There is no endpoint over either of them yet —
 * {@code GET /v1/admin/audit-logs} belongs to epic #100 — so they are here for the
 * suite and for whoever builds that surface, rather than being unused.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    /** What has been done to one thing, most recent first. */
    List<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId);

    /** What one account has done, most recent first. */
    List<AuditEntry> findByActorIdOrderByOccurredAtDesc(UUID actorId);
}
