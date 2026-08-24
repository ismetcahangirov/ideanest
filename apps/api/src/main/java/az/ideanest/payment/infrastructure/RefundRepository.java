package az.ideanest.payment.infrastructure;

import az.ideanest.payment.domain.Refund;
import az.ideanest.payment.domain.RefundState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V53's refunds — issues #67 and #307.
 *
 * <p><strong>{@link #refundedAgainst} is the one that matters.</strong> It is the read
 * behind the invariant V53's header says cannot be a constraint: the refunds against a
 * pledge may not exceed what was collected on it. That is a statement about a set of rows
 * here joined against a set of rows in {@code transactions}, which no {@code CHECK} can
 * express — so it is enforced in {@code RefundService} under a lock, and this is the sum
 * it enforces it with.
 *
 * <p>{@code FAILED} refunds are excluded from that sum, and {@code REQUESTED} ones are
 * not. A requested refund has not left yet but is about to, and counting only the
 * succeeded ones would let two staff members each issue a full refund in the seconds
 * before the first one settles.
 */
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /**
     * How much has been or is about to be refunded against one pledge.
     *
     * <p>{@code COALESCE} so an untouched pledge answers zero rather than null — a null
     * here would be compared against an amount by whichever caller forgot, and the
     * comparison that silently succeeds is the one that lets a second full refund
     * through.
     */
    @Query(
            """
            SELECT COALESCE(SUM(r.amount), 0) FROM Refund r
            WHERE r.pledgeId = :pledgeId
              AND r.state <> az.ideanest.payment.domain.RefundState.FAILED
            """)
    BigDecimal refundedAgainst(@Param("pledgeId") UUID pledgeId);

    /** A refund already recorded under this key, for the idempotent replay. */
    @Query("SELECT r FROM Refund r WHERE r.idempotencyKey = :key")
    Optional<Refund> byIdempotencyKey(@Param("key") String key);

    /**
     * The console's list, newest first.
     *
     * <p>Offset-paged rather than keyset, which is a departure from the rest of the
     * console and is deliberate. The audit trail and the payment log page over tables that
     * grow under the reader continuously, so an offset drifts them past rows. Refunds are
     * written a handful of times a day by the people reading this screen, and what those
     * readers want is to filter by state and jump — which keyset does not offer.
     */
    @Query("SELECT r FROM Refund r ORDER BY r.requestedAt DESC, r.id DESC")
    List<Refund> page(Pageable pageable);

    /** The same, narrowed to one state. Two queries rather than a nullable parameter. */
    @Query("SELECT r FROM Refund r WHERE r.state = :state ORDER BY r.requestedAt DESC, r.id DESC")
    List<Refund> pageByState(@Param("state") RefundState state, Pageable pageable);

    /**
     * How much has been refunded across a whole campaign — #69.
     *
     * <p>Subtracted from a payout gross. {@code FAILED} refunds are excluded and
     * {@code REQUESTED} ones are not, for {@link #refundedAgainst}'s reason: a refund that
     * has not left yet is about to, and paying a creator money that is on its way back to
     * a backer is the one mistake a payout must not make.
     */
    @Query(
            """
            SELECT COALESCE(SUM(r.amount), 0) FROM Refund r
            WHERE r.projectId = :projectId
              AND r.state <> az.ideanest.payment.domain.RefundState.FAILED
            """)
    BigDecimal refundedOnProject(@Param("projectId") UUID projectId);

    /** Every refund against one pledge, for the detail a support conversation needs. */
    @Query("SELECT r FROM Refund r WHERE r.pledgeId = :pledgeId ORDER BY r.requestedAt DESC")
    List<Refund> forPledge(@Param("pledgeId") UUID pledgeId);
}
