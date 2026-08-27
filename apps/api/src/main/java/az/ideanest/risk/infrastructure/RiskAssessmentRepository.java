package az.ideanest.risk.infrastructure;

import az.ideanest.risk.domain.RiskAssessment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Assessments, by the three questions asked of them — issue #108.
 *
 * <p>The queue, one subject's history, and the row a review marks seen. There is
 * deliberately no "every assessment" read: a list of everybody the platform has ever
 * scored is a report about its users rather than a work queue, and §17.4 has no purpose
 * for one.
 */
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    /**
     * §4.11's AD-02 queue: what needs looking at, worst first.
     *
     * <p>Matches {@code risk_assessments_queue_idx} exactly — unreviewed, not ALLOW,
     * ordered by score then recency. A query that ordered differently would read the table
     * instead of the index, and this one grows by a row per pledge on the platform.
     */
    @Query(
            """
            select assessment from RiskAssessment assessment
            where assessment.reviewedAt is null
              and assessment.decision <> az.ideanest.risk.domain.RiskDecision.ALLOW
            order by assessment.score desc, assessment.assessedAt desc
            """)
    List<RiskAssessment> queue(Pageable page);

    /**
     * Everything ever noticed about one subject, newest first.
     *
     * <p>A list rather than one row, because a re-assessment writes a new row — see
     * {@link RiskAssessment} on why the history is the point.
     */
    List<RiskAssessment> findBySubjectTypeAndSubjectIdOrderByAssessedAtDesc(String subjectType, UUID subjectId);

    /**
     * One unreviewed assessment.
     *
     * <p>The unreviewed part is in the query rather than checked afterwards, so that two
     * members of staff pressing the same button race to one winner instead of both
     * recording themselves as the reviewer.
     */
    Optional<RiskAssessment> findByIdAndReviewedAtIsNull(UUID id);
}
