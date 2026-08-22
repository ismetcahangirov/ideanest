package az.ideanest.payment.infrastructure;

import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.payment.domain.TransactionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Provider calls, appended and read back.
 *
 * <p>No update and no delete: V41 refuses both in PostgreSQL, and an interface that
 * does not offer them is how a developer finds that out before the trigger tells them.
 * {@code JpaRepository} brings {@code delete} along regardless — Spring Data's
 * interface is fixed — and calling it produces a {@code restrict_violation} at the
 * statement, which is the correct outcome even if it is a later one.
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    /**
     * Whether an attempt with this key has already been recorded.
     *
     * <p><strong>The guard in front of a second {@code PENDING} row.</strong> V41's
     * unique index is partial over the settled states, so it does not refuse two
     * {@code PENDING} rows for one key — deliberately, because a {@code PENDING} row and
     * the row that later settles it share a key and neither can be updated into the
     * other. What stops the {@code PENDING} rows accumulating on every re-poll is this
     * read plus the fact that every charge on a pledge is serialised by a lock on the
     * pledge row, which makes a read-then-write correct here in a way it usually is not.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /** An attempt by its key, for the re-poll that finds the provider has since decided. */
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Everything that happened to one pledge, newest first.
     *
     * <p>The support read — "why was this card refused four times" — and the only place
     * the decline codes live.
     */
    List<PaymentTransaction> findByPledgeIdOrderByCreatedAtDesc(UUID pledgeId);

    /** How many calls of one status a campaign has produced. For the collection's progress. */
    long countByProjectIdAndStatus(UUID projectId, TransactionStatus status);
}
