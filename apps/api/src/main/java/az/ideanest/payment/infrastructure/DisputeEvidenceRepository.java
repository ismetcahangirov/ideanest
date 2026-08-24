package az.ideanest.payment.infrastructure;

import az.ideanest.payment.domain.DisputeEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V54's evidence — issues #68 and #308.
 *
 * <p>Oldest first, which is the opposite of every other list in the console and is right
 * here: evidence is read as an argument, and an argument is read in the order it was
 * assembled.
 */
public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, UUID> {

    @Query("SELECT e FROM DisputeEvidence e WHERE e.disputeId = :disputeId ORDER BY e.createdAt ASC")
    List<DisputeEvidence> forDispute(@Param("disputeId") UUID disputeId);

    /** The pieces that have not been sent yet, for the submit that sends them together. */
    @Query(
            """
            SELECT e FROM DisputeEvidence e
            WHERE e.disputeId = :disputeId AND e.submittedAt IS NULL
            ORDER BY e.createdAt ASC
            """)
    List<DisputeEvidence> unsentFor(@Param("disputeId") UUID disputeId);
}
