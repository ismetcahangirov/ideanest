package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.RewardTier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reward tiers, by the campaign they belong to.
 *
 * <p>There is deliberately no <em>derived</em> method that writes
 * {@code claimed_quantity} or {@code reserved_quantity}. Spring Data would generate
 * one from a name, and it would be a path into the stock columns that takes no lock
 * and checks no limit. The two written by hand below are #51's, and each is a single
 * conditional statement that cannot be called wrongly: the condition that keeps the
 * tier from overselling is inside the {@code UPDATE}, not in the caller.
 */
public interface RewardTierRepository extends JpaRepository<RewardTier, UUID> {

    /**
     * The reward list of one campaign, in display order.
     *
     * <p>Ordered by {@code createdAt} within a {@code sortOrder} so that two tiers
     * that were never reordered — both at zero — come back in a stable order
     * rather than in whichever order the planner produced. A list that changes
     * order between two reads of the same data looks like a bug in the editor.
     */
    List<RewardTier> findByProjectIdOrderBySortOrderAscCreatedAtAsc(UUID projectId);

    long countByProjectId(UUID projectId);

    /**
     * The highest position in use, so a new tier can be appended.
     *
     * <p>Empty for a campaign with no tiers yet. Appending rather than inserting at
     * the top: a creator who adds a tier while looking at their list expects it
     * where they can see it was added, and reordering is a separate, explicit act.
     */
    @Query("SELECT max(tier.sortOrder) FROM RewardTier tier WHERE tier.projectId = :projectId")
    Optional<Integer> findHighestSortOrder(@Param("projectId") UUID projectId);

    /**
     * Takes one place on a tier, if the tier has one left. #51.
     *
     * <p><strong>This one statement is the reservation mechanism.</strong> The
     * {@code UPDATE} takes PostgreSQL's row lock on the tier, and under READ
     * COMMITTED it re-reads the row behind that lock and re-evaluates its
     * {@code WHERE} clause against what the transaction that held the lock actually
     * committed. So two checkouts racing for the last place are serialised by the
     * database: the second one's condition is false by the time it is evaluated, no
     * row is updated, and it is told the tier is full. A read followed by a write
     * cannot do that — both callers would read "one place left", and the code that
     * checked would not be wrong, merely not serialised.
     *
     * <p>{@code claimed_quantity} is in the condition as well as
     * {@code reserved_quantity}, because a place that has been confirmed is as taken
     * as one being held. It is the same expression as V7's
     * {@code reward_tiers_stock_is_within_the_limit}, which is the second line: if
     * this condition were ever wrong the constraint refuses the transaction rather
     * than letting the tier oversell.
     *
     * <p>A null {@code limit_quantity} is unlimited and always matches. The count is
     * still incremented, because §5.3 lets a creator add a limit later and the floor
     * it may be lowered to is the places already taken.
     *
     * <p><strong>{@code version} is deliberately not incremented.</strong> The column
     * is optimistic locking for the creator's fields, and the pledge module cannot
     * touch those — {@code claimed_quantity} and {@code reserved_quantity} are mapped
     * non-insertable and non-updatable, so there is no update here for an editor to
     * lose. Bumping it would make every checkout invalidate whatever edit the creator
     * had open, which on a busy campaign is an editor that cannot save. The rule it
     * might be thought to protect — that a limit is never lowered below what is taken
     * — is held by the check constraint against the row as it really is, not against
     * the copy the editor happens to be holding.
     *
     * <p>Native rather than JPQL, and no {@code clearAutomatically}: nothing reads a
     * {@code RewardTier} entity after this in the same transaction, and clearing the
     * persistence context here would detach whatever the caller was in the middle of
     * saving. The flush before it is kept, so a pending write cannot be ordered after
     * this statement.
     *
     * @return 1 when a place was taken, 0 when the tier is full or no longer exists
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET reserved_quantity = reserved_quantity + 1
                     WHERE id = :id
                       AND (limit_quantity IS NULL OR claimed_quantity + reserved_quantity < limit_quantity)
                    """,
            nativeQuery = true)
    int reserveOnePlace(@Param("id") UUID id);

    /**
     * Gives one held place back, when a reservation lapses or a draft is abandoned.
     *
     * <p>{@code reserved_quantity > 0} is a guard against a release running twice,
     * not against the constraint. {@code reward_tiers_reserved_is_not_negative} would
     * refuse a count below zero, but the damage of a double release happens above
     * zero, where it is silent: the tier would offer a place that no reservation ever
     * gave back, and the first person to take it would be the one who found out at
     * fulfilment.
     *
     * @return 1 when a place was given back, 0 when there was none to give
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE reward_tiers
                       SET reserved_quantity = reserved_quantity - 1
                     WHERE id = :id AND reserved_quantity > 0
                    """,
            nativeQuery = true)
    int releaseOnePlace(@Param("id") UUID id);
}
