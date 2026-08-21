package az.ideanest.project.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The two statements §8.4's deadline sweep is made of.
 *
 * <p><strong>Not a {@code JpaRepository}, and {@code deadline_notices} has no entity.</strong>
 * The table has no identity worth loading: it is a composite key, an instant, and no behaviour
 * — nothing reads a notice, nothing updates one, and the only question ever asked of it is
 * whether a claim succeeded. An entity would be a class whose whole purpose is to be counted.
 *
 * <p>The two statements below are the sweep, and both are deliberately in SQL:
 *
 * <ul>
 *   <li>{@link #nearing} is an anti-join across two tables, one of which belongs to this module
 *       and neither of which has a JPA association to the other. Expressed in JPQL it would be
 *       a correlated subquery over an entity that does not exist.
 *   <li>{@link #claim} is an {@code INSERT ... ON CONFLICT DO NOTHING}, which JPQL cannot say
 *       at all — and the conflict is the entire mechanism.
 * </ul>
 */
@Component
public class DeadlineNoticeRepository {

    /**
     * Live campaigns inside a threshold that have not been told yet.
     *
     * <p><strong>The window has a lower bound as well as an upper one</strong>, and the lower
     * one is what stops the sweep announcing a campaign whose deadline has already passed. A
     * campaign closes at its deadline and {@code campaign-finalizer} decides it within the
     * minute; between the deadline and that decision it is still {@code LIVE}, and without
     * {@code deadline > :now} the last tick before finalisation would send "24 hours remaining"
     * about a campaign that had already closed.
     *
     * <p>Ordered by deadline so a bounded pass takes the most urgent first: if the batch is too
     * small for the backlog, the campaign closing soonest is the one that gets its notice now
     * rather than the one with a lower identifier.
     *
     * <p><strong>Both parameters are cast explicitly, and neither cast is decoration.</strong> A
     * JDBC parameter arrives untyped, so {@code :now + make_interval(...)} is an unknown plus an
     * interval — PostgreSQL resolves that to interval arithmetic and then refuses to compare a
     * {@code timestamptz} to it. The message it gives ("operator does not exist: timestamp with
     * time zone <= interval") names the comparison rather than the parameter, which is a long
     * way from the cause.
     */
    private static final String NEARING =
            """
            SELECT p.id
              FROM projects p
             WHERE p.state = 'LIVE'
               AND p.deadline IS NOT NULL
               AND p.deadline > CAST(:now AS timestamptz)
               AND p.deadline <= CAST(:now AS timestamptz)
                                 + make_interval(hours => CAST(:thresholdHours AS integer))
               AND NOT EXISTS (
                     SELECT 1 FROM deadline_notices d
                      WHERE d.project_id = p.id
                        AND d.threshold_hours = CAST(:thresholdHours AS integer))
             ORDER BY p.deadline
             LIMIT :batchSize
            """;

    private static final String CLAIM =
            """
            INSERT INTO deadline_notices (project_id, threshold_hours, noticed_at)
            VALUES (:projectId, :thresholdHours, :at)
            ON CONFLICT DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeadlineNoticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Which campaigns are within {@code thresholdHours} of closing and have had no notice at
     * that threshold.
     *
     * <p>Identifiers rather than rows, because each is then claimed and announced in its own
     * transaction: a batch read in one transaction and acted on in another is a set of
     * decisions taken against a state that has since moved.
     */
    public List<UUID> nearing(int thresholdHours, Instant now, int batchSize) {
        return jdbc.queryForList(
                NEARING,
                new MapSqlParameterSource()
                        .addValue("now", java.sql.Timestamp.from(now))
                        .addValue("thresholdHours", thresholdHours)
                        .addValue("batchSize", batchSize),
                UUID.class);
    }

    /**
     * Claims a threshold for a campaign, if nobody has claimed it already.
     *
     * <p><strong>Call this inside the transaction that records the event.</strong> The claim
     * and the announcement are one fact; separated, a crash between them either announces a
     * campaign twice or claims a threshold whose message never went.
     *
     * @return true when this call is the one that claimed it, false when somebody else did.
     *     Both are ordinary — two replicas sweeping together is the arrangement, not a fault
     */
    public boolean claim(UUID projectId, int thresholdHours, Instant at) {
        return jdbc.update(
                        CLAIM,
                        new MapSqlParameterSource()
                                .addValue("projectId", projectId)
                                .addValue("thresholdHours", thresholdHours)
                                .addValue("at", java.sql.Timestamp.from(at)))
                == 1;
    }
}
