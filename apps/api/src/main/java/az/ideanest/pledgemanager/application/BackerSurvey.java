package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.Survey;
import az.ideanest.pledgemanager.domain.SurveyAnswer;
import az.ideanest.pledgemanager.domain.SurveyQuestion;
import az.ideanest.pledgemanager.domain.SurveyResponse;
import java.util.List;
import java.util.UUID;

/**
 * One survey as one backer sees it — §4.8's PM-05.
 *
 * <p><strong>The questions here are only the ones that apply to them</strong>, filtered
 * by PM-02 against the tier their pledge names. A client is never sent a question its
 * backer was not asked, so it cannot render one, and {@code SurveyResponseService}
 * refuses an answer to one — the filter is a fact, not a hint.
 *
 * @param pledgeId which of the account's pledges this is about. A backer with pledges
 *     on two campaigns has two of these; a backer cannot have two on one campaign,
 *     because {@code pledges_project_backer_active_key} says so
 * @param response null when they have not answered yet
 * @param open whether they may still answer or change it (PM-06). Computed against the
 *     clock at the moment of the read, so a client can disable a form rather than
 *     letting somebody type into one that will refuse them
 */
public record BackerSurvey(
        Survey survey,
        UUID pledgeId,
        List<SurveyQuestion> questions,
        SurveyResponse response,
        List<SurveyAnswer> answers,
        boolean open) {

    public BackerSurvey {
        questions = List.copyOf(questions);
        answers = List.copyOf(answers);
    }

    /** Whether this backer has answered at all. */
    public boolean isAnswered() {
        return response != null;
    }
}
