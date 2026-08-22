package az.ideanest.pledgemanager.application;

import java.util.UUID;

/**
 * A survey with nothing in it cannot be sent.
 *
 * <p>422: the request is well-formed and the caller is permitted to send this survey.
 * What is wrong is the survey — it would arrive in several thousand inboxes as a form
 * with no fields on it, and there is no state in which that is what somebody meant.
 */
public class SurveyHasNoQuestionsException extends RuntimeException {

    private final UUID surveyId;

    public SurveyHasNoQuestionsException(UUID surveyId) {
        super("Survey " + surveyId + " asks nothing");
        this.surveyId = surveyId;
    }

    public UUID surveyId() {
        return surveyId;
    }
}
