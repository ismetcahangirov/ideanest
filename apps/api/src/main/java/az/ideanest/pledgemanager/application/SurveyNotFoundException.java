package az.ideanest.pledgemanager.application;

import java.util.UUID;

/**
 * No such survey on this campaign.
 *
 * <p>404, and the same answer for a survey that does not exist and one belonging to a
 * different campaign: a caller who is not party to a campaign is not told what it is
 * preparing to ask its backers.
 */
public class SurveyNotFoundException extends RuntimeException {

    private final UUID surveyId;

    public SurveyNotFoundException(UUID surveyId) {
        super("No such survey: " + surveyId);
        this.surveyId = surveyId;
    }

    public UUID surveyId() {
        return surveyId;
    }
}
