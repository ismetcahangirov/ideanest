package az.ideanest.notification.infrastructure;

import az.ideanest.notification.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Notification rows, by the four questions anything asks of them. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Has this outbox event already been fanned out?
     *
     * <p><strong>The whole of the redelivery guarantee's fast path.</strong>
     * {@code OutboxMessage}'s contract is at-least-once, in those words, so the same
     * event arrives again whenever a relay crashes between the transport accepting a
     * message and the relay committing. The fan-out asks this first and does nothing
     * when the answer is yes.
     *
     * <p>One question for the whole event rather than one per (recipient, channel),
     * and that is correct because the fan-out is atomic: every row for one event is
     * written by one transaction, so either all of them exist or none do. The
     * {@code notifications_event_recipient_channel_key} index is what makes the answer
     * true under concurrency rather than merely usually right — two relays that both
     * got past this check meet on the unique index, and the second one fails.
     */
    boolean existsByEventId(UUID eventId);

    /**
     * The next notification this sender may take, locked so that no other sender can.
     *
     * <p>{@code OutboxEventRepository#claimNext} makes the argument in full and every
     * word of it applies here: the claim <em>is</em> the lock, because a read followed
     * by an update cannot be made correct — two senders read the same row, both conclude
     * it is theirs, and both send, and the second message is a duplicate nobody can see.
     * {@code SKIP LOCKED} rather than a wait, so several replicas divide the queue
     * between them instead of meeting on a row.
     *
     * <p><strong>No ordering clause, unlike the outbox.</strong> The relay refuses to
     * publish an event while an earlier pending one for the same aggregate exists,
     * because a consumer that saw {@code pledge.collected} before
     * {@code pledge.confirmed} would build a state that never existed. A notification is
     * not a state transition — it is a message to a person — and two of them arriving
     * out of order is a mild annoyance rather than a corruption, so the queue is ordered
     * by eligibility and nothing blocks behind anything.
     *
     * <p>{@code HELD} is not a candidate. That is the point of the state: a digest waits
     * for a combining job, and there is not one yet.
     *
     * <p>Native rather than JPQL because JPQL cannot express {@code SKIP LOCKED} at all.
     */
    @Query(
            value =
                    """
                    SELECT n.* FROM notifications n
                     WHERE n.state = 'PENDING'
                       AND n.next_attempt_at <= :now
                     ORDER BY n.next_attempt_at, n.id
                     LIMIT 1
                     FOR UPDATE OF n SKIP LOCKED
                    """,
            nativeQuery = true)
    Optional<Notification> claimNext(@Param("now") Instant now);

    /**
     * The first page of somebody's in-app inbox, newest first.
     *
     * <p>Keyset paging, like every other paged read in the service: an offset re-reads and
     * re-skips everything before it, and it also skips a row when something is inserted
     * between two requests — which for an inbox means a notification the reader never sees.
     *
     * <p>Ordered by {@code occurredAt} and then by the identifier. The identifier is a
     * UUID v7 and therefore time-ordered, which is what breaks a tie between two
     * notifications produced from one event in the same instant.
     *
     * <h2>Two methods rather than one with a null-tolerant cursor</h2>
     *
     * <p><strong>This was one query and the one query did not work.</strong> #85 wrote it as a
     * single method whose predicate began {@code :before IS NULL OR …}, which reads well and
     * fails at run time the moment it is called with no cursor: PostgreSQL cannot infer a type
     * for a parameter that appears only as the operand of {@code IS NULL}, and answers
     * {@code could not determine data type of parameter $2}. Nothing noticed, because until
     * #246 there was no endpoint and therefore no caller — a query with no caller is a query
     * nobody has run.
     *
     * <p>So the cursor and the absence of one are two statements. The cost is a duplicated
     * {@code WHERE} clause; the alternative is a cast in the JPQL, which would be one
     * expression that has to stay in step with the column's type and would still leave the
     * planner a parameter it cannot use for the index. These two are each exactly what
     * {@code notifications_inbox_idx} answers.
     */
    @Query(
            """
            SELECT n FROM Notification n
             WHERE n.recipientId = :recipientId
               AND n.channel = az.ideanest.notification.domain.NotificationChannel.IN_APP
               AND n.state = az.ideanest.notification.domain.NotificationState.SENT
             ORDER BY n.occurredAt DESC, n.id DESC
            """)
    List<Notification> inbox(@Param("recipientId") UUID recipientId, Pageable page);

    /**
     * The page below a cursor, newest first.
     *
     * @param before the {@code occurredAt} of the last row of the previous page
     * @param beforeId the identifier of that same row, which is what makes the cursor total
     *     rather than merely usually distinct
     */
    @Query(
            """
            SELECT n FROM Notification n
             WHERE n.recipientId = :recipientId
               AND n.channel = az.ideanest.notification.domain.NotificationChannel.IN_APP
               AND n.state = az.ideanest.notification.domain.NotificationState.SENT
               AND (n.occurredAt < :before
                    OR (n.occurredAt = :before AND n.id < :beforeId))
             ORDER BY n.occurredAt DESC, n.id DESC
            """)
    List<Notification> inboxBefore(
            @Param("recipientId") UUID recipientId,
            @Param("before") Instant before,
            @Param("beforeId") UUID beforeId,
            Pageable page);

    /** The one number an inbox badge is made of. */
    @Query(
            """
            SELECT count(n) FROM Notification n
             WHERE n.recipientId = :recipientId
               AND n.channel = az.ideanest.notification.domain.NotificationChannel.IN_APP
               AND n.state = az.ideanest.notification.domain.NotificationState.SENT
               AND n.readAt IS NULL
            """)
    long countUnread(@Param("recipientId") UUID recipientId);

    /**
     * One notification, if it is this person's.
     *
     * <p>The recipient is part of the query rather than checked after the read, so that
     * "not yours" and "does not exist" are the same answer and neither confirms the
     * existence of somebody else's row.
     */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
}
