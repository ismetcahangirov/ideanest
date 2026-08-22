package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.Survey;
import az.ideanest.pledgemanager.domain.SurveyAnswer;
import az.ideanest.pledgemanager.domain.SurveyQuestion;
import az.ideanest.pledgemanager.domain.SurveyResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A page of what came back, for the creator.
 *
 * <p>The questions travel with it because a response is a list of answers keyed by
 * question identifier, and a client that had to fetch the survey separately in order
 * to render a column heading would be one request away from rendering the wrong one.
 *
 * @param answers by response identifier, assembled in one query for the whole page
 * @param total how many responses exist, which is what a creator compares against the
 *     number the survey was sent to
 */
public record SurveyResponsePage(
        Survey survey,
        List<SurveyQuestion> questions,
        List<SurveyResponse> responses,
        Map<UUID, List<SurveyAnswer>> answers,
        long total) {

    public SurveyResponsePage {
        questions = List.copyOf(questions);
        responses = List.copyOf(responses);
        answers = Map.copyOf(answers);
    }
}
