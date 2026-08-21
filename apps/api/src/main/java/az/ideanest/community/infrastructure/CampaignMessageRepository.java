package az.ideanest.community.infrastructure;

import az.ideanest.community.domain.CampaignMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Messages sent to a campaign's backers, by the two questions asked of them.
 *
 * <p><strong>No delete and no update</strong>, and there is nothing to add: a message is
 * immutable once sent, which {@code CampaignMessage} argues, so {@code save} and the two reads
 * below are the whole of what this table needs.
 *
 * <p>Ordinary {@code save} rather than the {@code ON CONFLICT DO NOTHING} that
 * {@code SaveRepository} needs, because the two writes are different in kind. A save is a toggle
 * whose second press must be harmless; sending a message twice produces two messages, which is
 * what the caller asked for both times. What stops an accidental repeat is the rate limit, and
 * what stops a retried HTTP request becoming two is the idempotency key the endpoint takes.
 */
public interface CampaignMessageRepository extends JpaRepository<CampaignMessage, UUID> {

    /** The first page of this campaign's messages, newest first. */
    @Query(
            """
            SELECT m FROM CampaignMessage m
             WHERE m.projectId = :projectId
             ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<CampaignMessage> page(@Param("projectId") UUID projectId, Pageable limit);

    /** The page below a cursor. Two methods, for {@code SaveRepository#page}'s reason. */
    @Query(
            """
            SELECT m FROM CampaignMessage m
             WHERE m.projectId = :projectId
               AND (m.createdAt < :before
                    OR (m.createdAt = :before AND m.id < :beforeId))
             ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<CampaignMessage> pageBefore(
            @Param("projectId") UUID projectId,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable limit);
}
