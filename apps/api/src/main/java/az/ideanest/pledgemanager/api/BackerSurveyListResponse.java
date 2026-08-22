package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.BackerSurvey;
import java.util.List;

/**
 * Every survey this account is being asked.
 *
 * <p>Across every campaign they backed, and one entry per pledge rather than per
 * survey: a backer holds at most one pledge per campaign, so the two coincide today —
 * but the entry is about a pledge, because that is what decides which questions apply
 * and what the answers are filed under.
 */
public record BackerSurveyListResponse(List<BackerSurveyBody> surveys) {

    public static BackerSurveyListResponse of(List<BackerSurvey> surveys) {
        return new BackerSurveyListResponse(surveys.stream().map(BackerSurveyBody::of).toList());
    }
}
