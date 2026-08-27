package az.ideanest.risk.api;

import az.ideanest.risk.domain.RiskAssessment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * §4.11's AD-02 fraud signals, as the console reads them — issue #108.
 *
 * <h2>What is deliberately not in the response</h2>
 *
 * <p><strong>No address, no email, no name.</strong> A finding says "address not among the
 * 4 this account has used", never which address; {@code RiskFinding} carries the rule and
 * this is where it would be quietly broken by adding a convenience field. A member of staff
 * who needs the account opens the account, through {@code AdminUserController}, which
 * audits the read.
 *
 * <p><strong>No amount.</strong> The score is about behaviour rather than about money, and
 * a queue that sorted by value would be a queue that ignores the cheap card testing this
 * exists to catch.
 */
public final class RiskResponses {

    private RiskResponses() {}

    /**
     * One assessment.
     *
     * @param findings the signals, parsed back out of the stored document so that a client
     *     receives JSON rather than a string containing JSON
     * @param signalsUnavailable how many could not be evaluated. <strong>Beside the score
     *     and never folded into it</strong> — a 20 with two signals unavailable is a
     *     different statement from a 20 with none, and a console that showed only the
     *     number would be presenting a partial check as a clean one
     */
    public record Assessment(
            UUID id,
            String subjectType,
            UUID subjectId,
            UUID projectId,
            int score,
            String decision,
            JsonNode findings,
            int signalsUnavailable,
            Instant assessedAt,
            Instant reviewedAt) {

        public static Assessment of(RiskAssessment assessment, ObjectMapper json) {
            return new Assessment(
                    assessment.getId(),
                    assessment.getSubjectType(),
                    assessment.getSubjectId(),
                    assessment.getProjectId(),
                    assessment.getScore(),
                    assessment.getDecision().name(),
                    json.readTree(assessment.getFindings()),
                    assessment.getSignalsUnavailable(),
                    assessment.getAssessedAt(),
                    assessment.getReviewedAt());
        }
    }

    /**
     * The queue.
     *
     * <p>No cursor. The queue is what is unreviewed and above the threshold, which is
     * bounded by how fast people work rather than by how long the platform has run — a
     * second page means the queue is not being read, and the answer to that is not
     * pagination. {@code limit} is clamped by the caller.
     */
    public record Queue(List<Assessment> assessments) {

        public static Queue of(List<RiskAssessment> assessments, ObjectMapper json) {
            return new Queue(assessments.stream()
                    .map(assessment -> Assessment.of(assessment, json))
                    .toList());
        }
    }
}
