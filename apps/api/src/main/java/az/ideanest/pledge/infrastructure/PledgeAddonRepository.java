package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.domain.PledgeAddon;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The add-on lines of a pledge. §4.5's PL-04.
 *
 * <p>One question, because one is all that is asked today. #56's edit will need a
 * way to replace the set, and the creator's fulfilment report will need the lines of
 * many pledges at once; neither is guessed at here, for the reason
 * {@link PledgeRepository} gives — a derived method nobody calls is a query nobody
 * has looked at the plan for.
 */
public interface PledgeAddonRepository extends JpaRepository<PledgeAddon, PledgeAddon.Key> {

    /**
     * What this pledge selected beyond its reward tier.
     *
     * <p>Ordered by the tier's identifier, which is a UUID v7 and therefore the order
     * the tiers were created in. Any stable order would do; an unordered read would
     * mean two requests for one pledge could answer with the same add-ons listed
     * differently, and a client comparing the two would be told the pledge changed.
     */
    @Query("SELECT addon FROM PledgeAddon addon WHERE addon.id.pledgeId = :pledgeId ORDER BY addon.id.rewardTierId")
    List<PledgeAddon> findByPledge(@Param("pledgeId") UUID pledgeId);
}
