package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.SurveyNudge;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Who has already been chased about a survey — §4.8's PM-24. */
public interface SurveyNudgeRepository extends JpaRepository<SurveyNudge, SurveyNudge.Key> {

    /**
     * The most recent reminder each pledge has had for this survey, as
     * {@code (pledgeId, attempt, sentAt)}.
     *
     * <p>{@code Object[]} rather than a projection type, following
     * {@code RewardTierItemRepository.weighTiers}: three columns read in one place do
     * not earn a record of their own.
     */
    @Query("SELECT n.id.pledgeId, max(n.id.attempt), max(n.sentAt) FROM SurveyNudge n"
            + " WHERE n.id.surveyId = :surveyId GROUP BY n.id.pledgeId")
    List<Object[]> latestPerPledge(@Param("surveyId") UUID surveyId);
}
