package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.domain.SupplementAddon;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The lines of a post-campaign purchase — §4.8's PM-10 (#76). */
public interface SupplementAddonRepository extends JpaRepository<SupplementAddon, SupplementAddon.Key> {

    /**
     * The lines of several purchases at once.
     *
     * <p>A batch rather than one call per supplement, because the read that needs
     * them is a pledge's whole list: a backer with four purchases would otherwise be
     * four queries inside one response.
     */
    @Query("SELECT line FROM SupplementAddon line WHERE line.id.supplementId IN :supplementIds"
            + " ORDER BY line.id.supplementId, line.id.rewardTierId")
    List<SupplementAddon> findBySupplements(@Param("supplementIds") Collection<UUID> supplementIds);
}
