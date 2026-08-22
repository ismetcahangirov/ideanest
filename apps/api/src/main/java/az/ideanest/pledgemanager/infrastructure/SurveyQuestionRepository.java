package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.SurveyQuestion;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The questions on a survey, in order. */
public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestion, UUID> {

    /** Every read of a survey wants its questions in order, and none wants one alone. */
    @Query("SELECT q FROM SurveyQuestion q WHERE q.surveyId = :surveyId ORDER BY q.position")
    List<SurveyQuestion> findBySurvey(@Param("surveyId") UUID surveyId);

    /** The questions of several surveys, for a backer's list of everything they are being asked. */
    @Query("SELECT q FROM SurveyQuestion q WHERE q.surveyId IN :surveyIds ORDER BY q.surveyId, q.position")
    List<SurveyQuestion> findBySurveys(@Param("surveyIds") Collection<UUID> surveyIds);

    /**
     * Which surveys ask about this reward tier — PM-02.
     *
     * <p>Asked before a tier is deleted. V35 refuses that delete with a foreign key,
     * and this is what lets the refusal name the surveys so the creator knows where to
     * look.
     */
    @Query("SELECT q FROM SurveyQuestion q WHERE q.rewardTierId = :rewardTierId")
    List<SurveyQuestion> findByRewardTier(@Param("rewardTierId") UUID rewardTierId);
}
