package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.Survey;
import az.ideanest.pledgemanager.domain.SurveyQuestion;
import java.util.List;

/**
 * A survey with its questions and how many people have answered.
 *
 * <p>The response of every creator-facing survey endpoint — create, update, send, read
 * and list — so a client applies the same update to its state whichever call it made.
 * An endpoint that answered with a thinner shape would make a client's state depend on
 * which request it happened to send last, which is the argument {@code RewardDetail}
 * makes in this codebase already.
 *
 * @param responseCount how many backers have answered, or zero for a draft. Here
 *     rather than fetched separately because it is the number the creator is looking
 *     for, and a per-survey read on a list screen is n+1 queries deep
 */
public record SurveyDetail(Survey survey, List<SurveyQuestion> questions, long responseCount) {

    public SurveyDetail {
        questions = List.copyOf(questions);
    }
}
