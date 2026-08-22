package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.ShippingZoneRule;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Per-zone shipping rates, by the tier they price. */
public interface ShippingZoneRuleRepository extends JpaRepository<ShippingZoneRule, ShippingZoneRule.Key> {

    /** One tier's zone rates, for the editor that replaces them wholesale. */
    @Query("SELECT r FROM ShippingZoneRule r WHERE r.id.rewardTierId = :tierId")
    List<ShippingZoneRule> findByRewardTier(@Param("tierId") UUID tierId);

    /** Every zone rate for a whole reward list, in one query rather than one per tier. */
    @Query("SELECT r FROM ShippingZoneRule r WHERE r.id.rewardTierId IN :tierIds")
    List<ShippingZoneRule> findByRewardTiers(@Param("tierIds") Collection<UUID> tierIds);

    /**
     * The zone rates for a whole selection, in one query rather than one per tier.
     *
     * <p>Narrowed to a single zone because the checkout has already resolved which
     * zone the destination falls into — at most one, by the primary key on
     * {@code shipping_zone_countries} — so fetching every zone's rates would read
     * the campaign's whole rate table to use one row of it.
     */
    @Query("SELECT r FROM ShippingZoneRule r WHERE r.id.zoneId = :zoneId AND r.id.rewardTierId IN :tierIds")
    List<ShippingZoneRule> findByZoneAndRewardTiers(
            @Param("zoneId") UUID zoneId, @Param("tierIds") Collection<UUID> tierIds);
}
