package az.ideanest.community.infrastructure;

import az.ideanest.community.domain.ProjectUpdate;
import az.ideanest.community.domain.UpdateVisibility;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Updates, by the three questions asked of them.
 *
 * <p>Newest first everywhere, which is both the order the index is built in and the
 * order the Updates tab is read in: nobody opens a campaign to look at update 1.
 *
 * <p>There is deliberately no update or delete method. {@code ProjectUpdate} has no
 * setters either — see that class for why an update that could be rewritten would be a
 * weaker promise — and Spring Data will happily generate an update from a derived
 * name, so the absence has to be deliberate in both places.
 */
public interface ProjectUpdateRepository extends JpaRepository<ProjectUpdate, UUID> {

    /**
     * The newest update, locked, so the next number can be allocated behind it.
     *
     * <p><strong>The lock is what makes {@code max + 1} safe for every campaign that
     * already has one update.</strong> Two writers arriving together both read this
     * row; the second waits for the first to commit and then reads the row the first
     * inserted, so they allocate different numbers rather than the same one. The
     * campaign publishing its very first update has no row to lock, and that race is
     * resolved by {@code project_updates_number_key} instead — see
     * {@code ProjectUpdateService} for what the loser of it is told.
     *
     * <p>A lock on this table rather than on {@code projects}: a lock on the campaign
     * row would serialise an update against every other thing that reads it, on the row
     * every request in the platform touches, to protect a counter that concerns one
     * table.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM ProjectUpdate u WHERE u.projectId = :projectId ORDER BY u.number DESC LIMIT 1")
    Optional<ProjectUpdate> lockNewest(@Param("projectId") UUID projectId);

    /**
     * The newest update, unlocked, for the chronology rule.
     *
     * <p>Read inside the same transaction as {@link #lockNewest} would be a second
     * query for a row already held; this one exists for the read-only paths that want
     * to know the same thing without taking a lock.
     */
    Optional<ProjectUpdate> findFirstByProjectIdOrderByNumberDesc(UUID projectId);

    /**
     * One page of a campaign's updates, newest first.
     *
     * <p><strong>Every filter is a parameter rather than a method name.</strong> Who is
     * reading decides which visibilities are included and whether a scheduled update is
     * one of them, and the alternative — four derived query methods and an {@code if}
     * choosing between them — is four places for one of them to forget a clause. The
     * clause that must never be forgotten is {@code published_at <= now}, and it is
     * written once.
     *
     * @param visibilities what this caller may see. Never empty: a caller who may see
     *     nothing is refused before this is reached
     * @param publishedBefore the moment a row must not be later than to count as
     *     published. A far-future instant for a caller who may see scheduled updates,
     *     which is the campaign's own team
     * @param below the cursor: only updates numbered below this. {@link Integer#MAX_VALUE}
     *     for the first page, rather than null — a nullable bind would make the
     *     predicate {@code (:below IS NULL OR ...)}, and an untyped null parameter is
     *     the shape PostgreSQL refuses to infer a type for
     * @param limit one more than the page size, so the caller can tell "a full page" from
     *     "a full page and there is more" without a second count query
     */
    @Query(
            """
            SELECT u FROM ProjectUpdate u
             WHERE u.projectId = :projectId
               AND u.visibility IN :visibilities
               AND u.publishedAt <= :publishedBefore
               AND u.number < :below
             ORDER BY u.number DESC
            """)
    List<ProjectUpdate> page(
            @Param("projectId") UUID projectId,
            @Param("visibilities") Collection<UpdateVisibility> visibilities,
            @Param("publishedBefore") Instant publishedBefore,
            @Param("below") int below,
            Limit limit);
}
