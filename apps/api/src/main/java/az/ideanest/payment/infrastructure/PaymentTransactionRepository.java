package az.ideanest.payment.infrastructure;

import az.ideanest.payment.domain.PaymentTransaction;
import az.ideanest.payment.domain.TransactionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /*
     * The six below are AD-05's log — #304. Three filter shapes, each with a first page
     * and a keyset page; PaymentLogScope has the argument for why there are three.
     *
     * Ordered by identifier rather than by created_at, which is the same choice
     * AuditEntryRepository makes and for the same reason: the identifier is a UUID v7
     * carrying the millisecond it was minted in (§7.3), it is unique where the timestamp is
     * not, and a unique sort key is a cursor of one value instead of two. Four attempts
     * against one pledge inside the same second are the ordinary case here, not the edge.
     */

    /** The newest calls the platform has made, whatever they were about. */
    @Query("SELECT t FROM PaymentTransaction t ORDER BY t.id DESC")
    List<PaymentTransaction> newest(Pageable limit);

    /** The page after {@code before}. */
    @Query("SELECT t FROM PaymentTransaction t WHERE t.id < :before ORDER BY t.id DESC")
    List<PaymentTransaction> newestBefore(@Param("before") UUID before, Pageable limit);

    /** Everything that moved on one campaign, newest first. */
    @Query("SELECT t FROM PaymentTransaction t WHERE t.projectId = :projectId ORDER BY t.id DESC")
    List<PaymentTransaction> newestOfProject(@Param("projectId") UUID projectId, Pageable limit);

    /** The page after {@code before}, within one campaign. */
    @Query(
            """
            SELECT t FROM PaymentTransaction t
            WHERE t.projectId = :projectId AND t.id < :before
            ORDER BY t.id DESC
            """)
    List<PaymentTransaction> newestOfProjectBefore(
            @Param("projectId") UUID projectId, @Param("before") UUID before, Pageable limit);

    /**
     * One pledge's whole attempt history, newest first.
     *
     * <p>The same rows as {@link #findByPledgeIdOrderByCreatedAtDesc}, paged. That one
     * stays because the collection run reads it whole and wants no {@link Pageable}; this
     * one exists because a screen cannot.
     */
    @Query("SELECT t FROM PaymentTransaction t WHERE t.pledgeId = :pledgeId ORDER BY t.id DESC")
    List<PaymentTransaction> newestOfPledge(@Param("pledgeId") UUID pledgeId, Pageable limit);

    /** The page after {@code before}, within one pledge. */
    @Query(
            """
            SELECT t FROM PaymentTransaction t
            WHERE t.pledgeId = :pledgeId AND t.id < :before
            ORDER BY t.id DESC
            """)
    List<PaymentTransaction> newestOfPledgeBefore(
            @Param("pledgeId") UUID pledgeId, @Param("before") UUID before, Pageable limit);

    /**
     * The settled charge on a pledge, newest first — #67.
     *
     * <p>A refund is submitted against the original authorisation, so this is what finds
     * the {@code providerTransactionId} to send. Newest first because §9.6 permits up to
     * four collection attempts and only the successful one has an identifier worth
     * reversing; earlier rows are declines.
     *
     * <p>{@code SUCCEEDED} only, and {@code PENDING} deliberately excluded: refunding a
     * charge the provider has accepted but not settled is an instruction most providers
     * refuse, and the ones that accept it produce a refund and a charge that race.
     */
    @Query(
            """
            SELECT t FROM PaymentTransaction t
            WHERE t.pledgeId = :pledgeId
              AND t.type = az.ideanest.payment.domain.TransactionType.CHARGE
              AND t.status = az.ideanest.payment.domain.TransactionStatus.SUCCEEDED
            ORDER BY t.createdAt DESC
            """)
    List<PaymentTransaction> settledChargesOf(@Param("pledgeId") UUID pledgeId);

    /**
     * How much was actually collected on a pledge — #67's overdraft check.
     *
     * <p>The sum of its settled charges. Read from this table rather than from
     * {@code pledges.amount} because what may be refunded is what was taken, and those
     * differ whenever a collection was partial or a supplement was added after the fact.
     * It also keeps the payment module out of the pledge module's table.
     */
    @Query(
            """
            SELECT COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t
            WHERE t.pledgeId = :pledgeId
              AND t.type = az.ideanest.payment.domain.TransactionType.CHARGE
              AND t.status = az.ideanest.payment.domain.TransactionStatus.SUCCEEDED
            """)
    java.math.BigDecimal collectedOn(@Param("pledgeId") UUID pledgeId);

    /**
     * What has actually moved, per kind and per currency — §8.4's
     * {@code ledger-reconciliation}, issue #70.
     *
     * <p>An aggregate rather than a scan, because this runs daily over every transaction the
     * platform has ever recorded and the point of it is to be cheap enough to keep running as
     * that number grows. Grouped by currency and never summed across it, for §21.2's reason.
     *
     * <p><strong>{@code SUCCEEDED} only.</strong> A declined charge moved nothing and has no
     * ledger posting; counting it would make every failed collection look like an imbalance.
     * {@code PENDING} is excluded for the same reason and a sharper one — money the provider
     * has accepted and not settled is money in neither place yet, and a reconciliation that
     * counted it would fire every night between the two.
     */
    @Query(
            """
            SELECT new az.ideanest.payment.application.SettledTotal(t.type, t.currency, COALESCE(SUM(t.amount), 0))
            FROM PaymentTransaction t
            WHERE t.status = az.ideanest.payment.domain.TransactionStatus.SUCCEEDED
            GROUP BY t.type, t.currency
            ORDER BY t.currency, t.type
            """)
    List<az.ideanest.payment.application.SettledTotal> settledTotals();

    /**
     * Every settled charge on a campaign — #69's payout calculation.
     *
     * <p>The gross a payout is computed from. Ordered so that a payout's own record of
     * which rows it covered is reproducible.
     */
    @Query(
            """
            SELECT t FROM PaymentTransaction t
            WHERE t.projectId = :projectId
              AND t.type = az.ideanest.payment.domain.TransactionType.CHARGE
              AND t.status = az.ideanest.payment.domain.TransactionStatus.SUCCEEDED
            ORDER BY t.createdAt ASC
            """)
    List<PaymentTransaction> settledChargesOfProject(@Param("projectId") UUID projectId);
}
