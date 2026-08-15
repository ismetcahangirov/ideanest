package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.ShippingRule;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Per-country shipping rates, by the tier they price. */
public interface ShippingRuleRepository extends JpaRepository<ShippingRule, ShippingRule.Key> {

    /** In country order, so a creator reading their own rate table finds a row where they left it. */
    @Query("SELECT r FROM ShippingRule r WHERE r.id.rewardTierId = :tierId ORDER BY r.id.countryCode")
    List<ShippingRule> findByRewardTier(@Param("tierId") UUID tierId);

    /** Every rate for a whole reward list, in one query rather than one per tier. */
    @Query("SELECT r FROM ShippingRule r WHERE r.id.rewardTierId IN :tierIds ORDER BY r.id.countryCode")
    List<ShippingRule> findByRewardTiers(@Param("tierIds") Collection<UUID> tierIds);
}
