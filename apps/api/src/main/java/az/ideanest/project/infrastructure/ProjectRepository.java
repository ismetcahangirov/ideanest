package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.Project;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Campaigns, by the things they are actually looked up by.
 *
 * <p>There is deliberately no method that writes {@code state}. Spring Data will
 * generate an update from a derived name — {@code updateStateById} would compile
 * and work — and it would be a second path into the state column that writes no
 * audit row. The only path is {@code ProjectTransitionService} mutating the
 * entity inside a transaction.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /**
     * Whether this creator already has a campaign under this slug.
     *
     * <p>Scoped to the creator because the unique index is: the public URL is
     * {@code /projects/{creatorSlug}/{projectSlug}}, so two creators may both
     * have a "coffee-table-book".
     */
    boolean existsByCreatorIdAndSlug(UUID creatorId, String slug);

    /**
     * The row, locked until the transaction ends.
     *
     * <p>Used by every state change. Two moderators opening the same submission
     * and clicking approve and reject would otherwise both read {@code SUBMITTED},
     * both find their edge allowed, and both write an audit row — leaving a
     * campaign whose history says it was approved and rejected from the same
     * state, and whose actual state is whichever transaction committed last.
     * With the lock the second one waits, re-reads {@code APPROVED}, and is
     * refused by the transition table.
     *
     * <p>A pessimistic lock rather than a {@code @Version} column: the contested
     * case is rare, the transaction is short, and optimistic locking would
     * surface as a 409 the moderator has to interpret rather than as a wait they
     * never notice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);
}
