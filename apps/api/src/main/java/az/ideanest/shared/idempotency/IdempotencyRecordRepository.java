package az.ideanest.shared.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Idempotency records, by the two questions the machinery asks of them. */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    /**
     * The record of this key, if this account has spent it.
     *
     * <p>Read only after an insert has been refused, never before one is attempted.
     * A read that decided whether to execute would be exactly the check-then-act
     * this whole design exists to avoid: two identical requests would both find
     * nothing and both proceed. The unique index decides; this says what happened.
     *
     * <p>The account is part of the question rather than a check afterwards, which
     * is what makes one caller's key unreachable from another's request.
     */
    Optional<IdempotencyRecord> findByAccountIdAndIdempotencyKey(UUID accountId, String idempotencyKey);

    /**
     * Removes a bounded batch of keys whose 24 hours (§17.2) have elapsed.
     *
     * <p><strong>One statement, and no claim, unlike the reservation sweep.</strong>
     * {@code ReservationExpiry} claims each row with a conditional update because
     * expiring a draft has a second effect — the tier's place has to be given back —
     * and the two must not happen twice or half. Deleting a key has no second
     * effect at all: the delete <em>is</em> the work, and two replicas sweeping at
     * once simply means one of them deletes the row and the other reports nothing to
     * do. So there is nothing to claim and no transaction to keep in step, and the
     * whole sweep is this.
     *
     * <p>Bounded by a subquery with {@code LIMIT} because JPQL has no way to bound a
     * delete, and a backlog built up during an outage must not be one transaction
     * that overlaps its own next tick. Oldest first, so the rows that have been dead
     * longest go first.
     *
     * @return how many keys this pass removed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    DELETE FROM idempotency_keys
                     WHERE id IN (
                        SELECT id FROM idempotency_keys
                         WHERE expires_at <= :now
                         ORDER BY expires_at
                         LIMIT :batchSize)
                    """,
            nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
