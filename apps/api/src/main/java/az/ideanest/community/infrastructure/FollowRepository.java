package az.ideanest.community.infrastructure;

import az.ideanest.community.domain.Follow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Follows, by the four questions asked of them.
 *
 * <p>The mirror of {@link SaveRepository}, and every decision there is this one's:
 * registration is an {@code ON CONFLICT DO NOTHING} insert because idempotency is the whole
 * promise, unfollowing deletes the row, and the audience read is bounded and ordered by
 * identifier so that a redelivered event reaches the same people.
 */
public interface FollowRepository extends JpaRepository<Follow, UUID> {

    /**
     * Follows an account, or does nothing because it is already followed.
     *
     * @return 1 when this call created the row, 0 when it already existed. Both are success
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO follows (id, creator_id, follower_id)
                    VALUES (:id, :creatorId, :followerId)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("creatorId") UUID creatorId, @Param("followerId") UUID followerId);

    /** Unfollows. Idempotent by construction. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Follow f WHERE f.creatorId = :creatorId AND f.followerId = :followerId")
    int delete(@Param("creatorId") UUID creatorId, @Param("followerId") UUID followerId);

    /** Whether this account follows that one, which a creator's public page asks per view. */
    boolean existsByCreatorIdAndFollowerId(UUID creatorId, UUID followerId);

    /** The first page of the accounts this account follows, newest first. */
    @Query(
            """
            SELECT f FROM Follow f
             WHERE f.followerId = :followerId
             ORDER BY f.createdAt DESC, f.id DESC
            """)
    List<Follow> page(@Param("followerId") UUID followerId, Pageable limit);

    /** The page below a cursor. Two methods, for {@link SaveRepository#page}'s reason. */
    @Query(
            """
            SELECT f FROM Follow f
             WHERE f.followerId = :followerId
               AND (f.createdAt < :before
                    OR (f.createdAt = :before AND f.id < :beforeId))
             ORDER BY f.createdAt DESC, f.id DESC
            """)
    List<Follow> pageBefore(
            @Param("followerId") UUID followerId,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable limit);

    /**
     * #245's {@code FOLLOWERS}: everybody following this account.
     *
     * <p>Served by {@code follows_one_per_pair}, which indexes {@code (creator_id,
     * follower_id)} in that order — so this read needs no index of its own, which is why
     * {@code V32} creates only the follower-side one.
     */
    @Query("SELECT f.followerId FROM Follow f WHERE f.creatorId = :creatorId ORDER BY f.followerId")
    List<UUID> followerIds(@Param("creatorId") UUID creatorId, Pageable limit);
}
