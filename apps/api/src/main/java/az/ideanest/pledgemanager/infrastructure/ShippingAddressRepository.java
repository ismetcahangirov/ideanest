package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.ShippingAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Where each pledge's reward is posted — §4.8's PM-07 and PM-08. */
public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, UUID> {

    /** Every address on a campaign, for the creator's fulfilment list. */
    @Query("SELECT a FROM ShippingAddress a WHERE a.projectId = :projectId ORDER BY a.pledgeId")
    List<ShippingAddress> findByProject(@Param("projectId") UUID projectId);

    /** How many of a campaign's addresses the backer may still edit — the number a creator watches. */
    @Query("SELECT count(a) FROM ShippingAddress a WHERE a.projectId = :projectId AND a.lockedAt IS NULL")
    long countUnlocked(@Param("projectId") UUID projectId);

    /**
     * PM-08 in bulk: freezes every address on a campaign that is not already frozen.
     *
     * <p>One statement rather than a loop, because a creator locking four thousand
     * addresses one entity at a time is four thousand round trips inside one
     * transaction — and because the operation is "everything as of now", which a loop
     * cannot promise while backers are still writing.
     *
     * <p><strong>Only the unlocked ones.</strong> An address already frozen keeps the
     * instant and the person who froze it, so a second bulk lock does not rewrite the
     * history of the first — which is what a support conversation about "when was my
     * address locked" is answered from.
     *
     * @return how many were frozen by this call, which is what the response reports
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ShippingAddress a SET a.lockedAt = :at, a.lockedBy = :by"
            + " WHERE a.projectId = :projectId AND a.lockedAt IS NULL")
    int lockAll(@Param("projectId") UUID projectId, @Param("by") UUID by, @Param("at") Instant at);
}
