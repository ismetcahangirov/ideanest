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

    /**
     * What each of these tiers weighs, in grams — §4.8's PM-12 (#77).
     *
     * <p>V7 put {@code weight_grams} on the item and said out loud that a tier's
     * weight is "the sum of what is in it, which is a query rather than a column
     * somebody maintains". This is that query, and it is the only thing that has
     * ever read the column.
     *
     * <p><strong>Summed in the database, not in Java.</strong> A tier with eleven
     * items would otherwise be eleven rows fetched to add up eleven numbers, on the
     * request a backer is waiting on at checkout.
     *
     * <p><strong>{@code coalesce}, so a tier with unweighed items weighs zero rather
     * than nothing.</strong> The weight is optional in V7 and most campaigns never
     * fill it in; a null here would have to be interpreted by every caller, and the
     * interpretation would be zero. A tier absent from the result is a tier with no
     * items at all — a plain thank-you — which is the same answer.
     *
     * <p>Returns {@code Object[]} pairs rather than a projection interface, matching
     * how the other aggregates in this package come back: the shape is two columns
     * and a record would be a type declared to be read once.
     */
    @Query(
            """
            SELECT c.id.rewardTierId, coalesce(sum(coalesce(i.weightGrams, 0) * c.quantity), 0)
              FROM RewardTierItem c
              JOIN Item i ON i.id = c.id.itemId
             WHERE c.id.rewardTierId IN :tierIds
             GROUP BY c.id.rewardTierId
            """)
    List<Object[]> weighTiers(@Param("tierIds") Collection<UUID> tierIds);
}
