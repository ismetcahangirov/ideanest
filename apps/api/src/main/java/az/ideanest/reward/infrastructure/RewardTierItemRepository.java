package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.RewardTierItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The composition of reward tiers.
 *
 * <p>Written as explicit queries rather than as derived method names. The
 * identifier is an {@code @EmbeddedId}, so a derived name would have to spell a
 * nested property — {@code findByIdRewardTierId} — which reads as a question about
 * a field called {@code idRewardTierId} and breaks silently if the embeddable is
 * ever renamed. The query says what it means.
 */
public interface RewardTierItemRepository extends JpaRepository<RewardTierItem, RewardTierItem.Key> {

    @Query("SELECT c FROM RewardTierItem c WHERE c.id.rewardTierId = :tierId ORDER BY c.id.itemId")
    List<RewardTierItem> findByRewardTier(@Param("tierId") UUID tierId);

    /**
     * The composition of several tiers at once.
     *
     * <p>One query for a whole reward list. Asking per tier is an N+1 over a list
     * every editor session and every campaign page loads, which is the query
     * pattern that is only noticed once there are enough sessions for it to matter.
     */
    @Query("SELECT c FROM RewardTierItem c WHERE c.id.rewardTierId IN :tierIds ORDER BY c.id.itemId")
    List<RewardTierItem> findByRewardTiers(@Param("tierIds") Collection<UUID> tierIds);

    /**
     * Which tiers include this item.
     *
     * <p>Asked before an item is deleted. Deleting an item a tier contains would
     * silently change what a backer was promised, so the answer to this is what the
     * endpoint refuses with, and it names the tiers so the creator knows where to
     * look.
     */
    @Query("SELECT c FROM RewardTierItem c WHERE c.id.itemId = :itemId")
    List<RewardTierItem> findByItem(@Param("itemId") UUID itemId);
}
