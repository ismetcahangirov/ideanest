package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.RewardTier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reward tiers, by the campaign they belong to.
 *
 * <p>There is deliberately no method that writes {@code claimed_quantity} or
 * {@code reserved_quantity}. Spring Data would generate one from a derived name,
 * and it would be a path into the stock columns that takes no lock and checks no
 * limit. #51 owns those writes and will do them under a row lock.
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
}
