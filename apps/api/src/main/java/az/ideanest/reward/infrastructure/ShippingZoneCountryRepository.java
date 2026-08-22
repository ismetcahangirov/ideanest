package az.ideanest.reward.infrastructure;

import az.ideanest.reward.domain.ShippingZoneCountry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Which destinations each of a campaign's zones covers. */
public interface ShippingZoneCountryRepository extends JpaRepository<ShippingZoneCountry, ShippingZoneCountry.Key> {

    /** Every membership on a campaign, which is how the rate editor renders the zone list. */
    @Query("SELECT c FROM ShippingZoneCountry c WHERE c.id.projectId = :projectId ORDER BY c.id.countryCode")
    List<ShippingZoneCountry> findByProject(@Param("projectId") UUID projectId);

    /** The memberships of one zone, for the editor that replaces them wholesale. */
    @Query("SELECT c FROM ShippingZoneCountry c WHERE c.zoneId = :zoneId ORDER BY c.id.countryCode")
    List<ShippingZoneCountry> findByZone(@Param("zoneId") UUID zoneId);

    /**
     * Which zone, if any, a destination falls into on this campaign.
     *
     * <p>The one query the checkout runs, and the reason the primary key is
     * {@code (project_id, country_code)}: it is a primary-key lookup that can return
     * at most one row, so there is no ordering to decide and no precedence to
     * resolve between two zones that both claim the destination.
     */
    @Query("SELECT c FROM ShippingZoneCountry c"
            + " WHERE c.id.projectId = :projectId AND c.id.countryCode = :countryCode")
    Optional<ShippingZoneCountry> findDestination(
            @Param("projectId") UUID projectId, @Param("countryCode") String countryCode);
}
