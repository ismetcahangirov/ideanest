package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.SurveyResponse;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** What backers answered — §4.8's PM-05 and PM-06. */
public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, UUID> {

    /** The one row a backer may write, if it exists. */
    @Query("SELECT r FROM SurveyResponse r WHERE r.surveyId = :surveyId AND r.pledgeId = :pledgeId")
    Optional<SurveyResponse> findForPledge(@Param("surveyId") UUID surveyId, @Param("pledgeId") UUID pledgeId);

    /** The creator's page: who has answered, most recently first. */
    @Query("SELECT r FROM SurveyResponse r WHERE r.surveyId = :surveyId ORDER BY r.submittedAt DESC, r.id DESC")
    List<SurveyResponse> page(@Param("surveyId") UUID surveyId, Pageable limit);

    /** How many people have answered, which is the number a creator watches. */
    @Query("SELECT count(r) FROM SurveyResponse r WHERE r.surveyId = :surveyId")
    long countBySurvey(@Param("surveyId") UUID surveyId);

    /**
     * Which of a survey's pledges have answered.
     *
     * <p>Identifiers rather than rows: the reminder sweep subtracts the answered from
     * the sent, and loading a response to discard it would fetch every answer on the
     * campaign to work out who is missing.
     */
    @Query("SELECT r.pledgeId FROM SurveyResponse r WHERE r.surveyId = :surveyId")
    List<UUID> findAnsweredPledges(@Param("surveyId") UUID surveyId);

    /** This backer's responses across a set of surveys, for their own list. */
    @Query("SELECT r FROM SurveyResponse r WHERE r.backerId = :backerId AND r.surveyId IN :surveyIds")
    List<SurveyResponse> findByBackerAndSurveys(
            @Param("backerId") UUID backerId, @Param("surveyIds") Collection<UUID> surveyIds);
}
