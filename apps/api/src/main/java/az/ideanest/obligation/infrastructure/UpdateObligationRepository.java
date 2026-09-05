package az.ideanest.obligation.infrastructure;

import az.ideanest.obligation.domain.UpdateObligation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V68's clocks, by the three questions asked of them — #437.
 *
 * <p><strong>The sweep asks {@link #owing}</strong>, which is the hot one, and it takes an
 * instant rather than reading the clock: a pass has one "now", and two queries inside it that
 * each read their own would disagree by milliseconds at exactly the boundary the pass exists to
 * find. Bounded, following {@code DeadlineNoticeRepository}: a batch is a candidate list and
 * every candidate is re-checked by its own claim.
 *
 * <p><strong>The console asks {@link #escalated}</strong> — lapses nobody has looked at, oldest
 * first, because the campaign that has been silent longest is the one a backer is most likely to
 * be asking about.
 *
 * <p><strong>The profile asks {@link #forCreator}</strong>. Unpaged: §22.3's "creator's project
 * history" is every campaign they have run, and a creator with enough of them to need a cursor
 * has a profile that is already a different design problem.
 */
public interface UpdateObligationRepository extends JpaRepository<UpdateObligation, UUID> {

    /**
     * Running obligations whose next event has arrived — the reminder's or the lapse's.
     *
     * <p>One query for both, rather than one each, because they are the same scan of the same
     * partial index and the claims are what tell them apart. Two queries would read the table
     * twice per pass to answer a question the row already carries.
     */
    @Query(
            """
            SELECT o FROM UpdateObligation o
            WHERE o.closedAt IS NULL
              AND o.dueAt <= :horizon
            ORDER BY o.dueAt
            """)
    List<UpdateObligation> owing(@Param("horizon") Instant horizon, Limit limit);

    /** Lapses a moderator has not closed, oldest first. */
    @Query(
            """
            SELECT o FROM UpdateObligation o
            WHERE o.lapsedAt IS NOT NULL
              AND o.resolvedAt IS NULL
            ORDER BY o.lapsedAt
            """)
    List<UpdateObligation> escalated(Limit limit);

    /** Every campaign this creator has run since the clock existed, newest first. */
    @Query(
            """
            SELECT o FROM UpdateObligation o
            WHERE o.creatorId = :creatorId
            ORDER BY o.openedAt DESC
            """)
    List<UpdateObligation> forCreator(@Param("creatorId") UUID creatorId);
}
