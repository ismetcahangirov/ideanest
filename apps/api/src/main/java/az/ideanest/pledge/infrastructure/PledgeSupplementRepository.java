package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.domain.PledgeSupplement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** What a backer bought after the campaign closed — §4.8's PM-09 and PM-10 (#76). */
public interface PledgeSupplementRepository extends JpaRepository<PledgeSupplement, UUID> {

    /**
     * One pledge's purchases, oldest first.
     *
     * <p>The order is the order they were made in, which is what a backer reading
     * "what do I still owe" is looking at. {@code created_at} rather than the
     * identifier, although both would sort the same way today: the identifier is a
     * UUID v7 and that is an implementation detail of {@code Identifiers}, while the
     * column is the fact.
     */
    @Query("SELECT s FROM PledgeSupplement s WHERE s.pledgeId = :pledgeId ORDER BY s.createdAt, s.id")
    List<PledgeSupplement> findByPledge(@Param("pledgeId") UUID pledgeId);
}
