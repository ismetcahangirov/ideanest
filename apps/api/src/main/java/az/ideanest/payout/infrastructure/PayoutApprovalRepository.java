package az.ideanest.payout.infrastructure;

import az.ideanest.payout.domain.PayoutApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V55's signatures — issues #69 and #306.
 *
 * <p>The insert is {@code ON CONFLICT DO NOTHING}, so a member of staff approving twice
 * does not produce a constraint violation on a request that got what it asked for — and,
 * more to the point, does not become a second signature. The dual-approval rule is two
 * <em>different</em> people, and V55's primary key is what enforces it.
 */
public interface PayoutApprovalRepository extends JpaRepository<PayoutApproval, PayoutApproval.Key> {

    /** Who has signed, oldest first. */
    @Query("SELECT a FROM PayoutApproval a WHERE a.payoutId = :payoutId ORDER BY a.approvedAt ASC")
    List<PayoutApproval> forPayout(@Param("payoutId") UUID payoutId);

    /** How many distinct people have signed. */
    @Query("SELECT COUNT(a) FROM PayoutApproval a WHERE a.payoutId = :payoutId")
    long countFor(@Param("payoutId") UUID payoutId);

    /**
     * Records a signature, or does nothing because this person has already given one.
     *
     * @return 1 when this call created the row, 0 when it was already there. The caller
     *     uses it to tell "you have signed" from "you had already signed", which are
     *     different sentences to put in front of somebody and the same state afterwards
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO payout_approvals (payout_id, approver_id, note)
                    VALUES (:payoutId, :approverId, :note)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int approveIfAbsent(
            @Param("payoutId") UUID payoutId,
            @Param("approverId") UUID approverId,
            @Param("note") String note);

    /** Withdraws a signature. The audit trail is the only account of it afterwards. */
    @Modifying
    @Query("DELETE FROM PayoutApproval a WHERE a.payoutId = :payoutId AND a.approverId = :approverId")
    int withdraw(@Param("payoutId") UUID payoutId, @Param("approverId") UUID approverId);
}
