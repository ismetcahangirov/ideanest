package az.ideanest.pledgemanager.infrastructure;

import az.ideanest.pledgemanager.domain.SurveyAnswer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The answers themselves. */
public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, SurveyAnswer.Key> {

    @Query("SELECT a FROM SurveyAnswer a WHERE a.id.responseId = :responseId")
    List<SurveyAnswer> findByResponse(@Param("responseId") UUID responseId);

    /**
     * The answers behind a whole page of responses, in one query.
     *
     * <p>Not one per response. A page of two hundred would be two hundred extra round
     * trips on the screen a creator opens in order to plan a manufacturing run.
     */
    @Query("SELECT a FROM SurveyAnswer a WHERE a.id.responseId IN :responseIds")
    List<SurveyAnswer> findByResponses(@Param("responseIds") Collection<UUID> responseIds);
}
