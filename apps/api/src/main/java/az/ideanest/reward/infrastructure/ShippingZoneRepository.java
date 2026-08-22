package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.ShippingZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The regions a campaign has named — §4.8's PM-13. */
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, UUID> {

    /** In name order, which is the order the rate editor renders them in. */
    @Query("SELECT z FROM ShippingZone z WHERE z.projectId = :projectId ORDER BY lower(z.name)")
    List<ShippingZone> findByProject(@Param("projectId") UUID projectId);

    /**
     * One zone, and only if it is this campaign's.
     *
     * <p>The campaign is part of the query rather than a check on the result: a
     * caller that loaded by identifier alone and then compared would have read
     * another campaign's row into memory first, which is the shape of every
     * cross-tenant leak that ever shipped.
     */
    @Query("SELECT z FROM ShippingZone z WHERE z.id = :zoneId AND z.projectId = :projectId")
    Optional<ShippingZone> findOnProject(@Param("projectId") UUID projectId, @Param("zoneId") UUID zoneId);
}
