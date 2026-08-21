package az.ideanest.community.infrastructure;

import az.ideanest.community.domain.Save;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Saved campaigns, by the four questions asked of them.
 *
 * <p><strong>There is no {@code save}-shaped registration</strong>, for
 * {@code ReminderRepository}'s reason: saving is {@link #insertIfAbsent}, a native
 * {@code INSERT ... ON CONFLICT DO NOTHING}, because idempotency is the whole of what the
 * endpoint promises and a read-then-write check in Java loses the race between two taps.
 * {@code saves_one_per_account} is the check; this statement is how it gets to be it.
 *
 * <p><strong>And no soft delete.</strong> Un-saving removes the row — see the header of
 * {@code V32} for why that is a deliberate departure from §7.3.
 */
public interface SaveRepository extends JpaRepository<Save, UUID> {

    /**
     * Saves a campaign, or does nothing because it is already saved.
     *
     * <p>Native because JPQL has no {@code ON CONFLICT}, and the conflict is the point: the
     * database decides whether this call created the row, and both outcomes are success as far
     * as the caller is concerned.
     *
     * @return 1 when this call created the row, 0 when it already existed. Both are success;
     *     the caller uses it only to decide what to log
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO saves (id, project_id, user_id)
                    VALUES (:id, :projectId, :userId)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("projectId") UUID projectId, @Param("userId") UUID userId);

    /**
     * Un-saves a campaign. Idempotent by construction — nothing to remove is the state the
     * caller asked for.
     *
     * @return how many rows went, so the endpoint can log a removal that removed something
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Save s WHERE s.projectId = :projectId AND s.userId = :userId")
    int delete(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    /** Whether this account has saved this campaign, which the campaign page asks per view. */
    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    /**
     * The first page of this account's saved campaigns, newest first.
     *
     * <p>Keyset rather than offset, as every page in this codebase is: the cursor is
     * {@code (createdAt, id)}, and the tie-break on the identifier is what stops two campaigns
     * saved in the same instant from making a page boundary skip one and repeat the other.
     * {@code saves_account_idx} is ordered to match, so the read is a range scan and no sort.
     *
     * <p><strong>Two methods rather than one with a null-tolerant cursor</strong>, and
     * {@code NotificationRepository#inbox} explains why at length after having shipped the
     * other version: PostgreSQL cannot infer a type for a parameter that appears only as the
     * operand of {@code IS NULL}, so a single query with {@code :before IS NULL OR …} reads
     * well and fails at run time on the very first call. The cost is a duplicated predicate.
     */
    @Query(
            """
            SELECT s FROM Save s
             WHERE s.userId = :userId
             ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Save> page(@Param("userId") UUID userId, Pageable limit);

    /**
     * The page below a cursor, newest first.
     *
     * @param before the {@code createdAt} of the last row of the previous page
     * @param beforeId the identifier of that same row, which is what makes the cursor total
     *     rather than merely usually distinct
     */
    @Query(
            """
            SELECT s FROM Save s
             WHERE s.userId = :userId
               AND (s.createdAt < :before
                    OR (s.createdAt = :before AND s.id < :beforeId))
             ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Save> pageBefore(
            @Param("userId") UUID userId,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable limit);

    /**
     * #245's {@code SAVERS}: everybody who saved this campaign.
     *
     * <p>Identifiers rather than entities, and ordered by the identifier rather than by when
     * they saved it, because the interface promises a <em>stable</em> order and nothing else: a
     * truncated audience that returned a different subset on every call would mean a redelivered
     * event told a different set of people.
     */
    @Query("SELECT s.userId FROM Save s WHERE s.projectId = :projectId ORDER BY s.userId")
    List<UUID> saverIds(@Param("projectId") UUID projectId, Pageable limit);
}
