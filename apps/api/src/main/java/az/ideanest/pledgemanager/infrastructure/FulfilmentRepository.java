package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.Fulfilment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Where each pledge's parcel has got to — §4.8's PM-20 to PM-22. */
public interface FulfilmentRepository extends JpaRepository<Fulfilment, UUID> {

    /** Every parcel on a campaign, in the order V38's index holds them. */
    @Query("SELECT f FROM Fulfilment f WHERE f.projectId = :projectId ORDER BY f.pledgeId")
    List<Fulfilment> findByProject(@Param("projectId") UUID projectId);

    /**
     * The parcels for a set of pledges, which is how a backer's own list is built.
     *
     * <p>By pledge and never by backer, although the pledge knows its backer: this
     * module may not read {@code pledges}, so the identifiers arrive from
     * {@code BackedPledges} and the filtering has already happened there. A
     * {@code backer_id} column here would be a second copy of a fact that already has
     * an owner, and V38 declines to hold one.
     */
    @Query("SELECT f FROM Fulfilment f WHERE f.pledgeId IN :pledgeIds ORDER BY f.updatedAt DESC")
    List<Fulfilment> findByPledges(@Param("pledgeIds") Collection<UUID> pledgeIds);

    /**
     * How many of a campaign's parcels are in each status.
     *
     * <p>One grouped statement rather than four counts, and rather than folding the
     * rows the list read: the progress endpoint exists precisely so that a creator
     * with four thousand backers can poll a number without the platform materialising
     * four thousand rows to produce it.
     */
    @Query("SELECT f.status, count(f) FROM Fulfilment f WHERE f.projectId = :projectId GROUP BY f.status")
    List<Object[]> countByStatus(@Param("projectId") UUID projectId);
}
