package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.BackerSurvey;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One survey as one backer sees it — §4.8's PM-05 and PM-06.
 *
 * <p><strong>The questions are only the ones that apply to this pledge.</strong> PM-02
 * filters them against the tier the backer chose, on the server, so a client cannot
 * render a question its backer was not asked and the submission endpoint refuses one
 * anyway.
 *
 * <p>Deliberately not {@code SurveyResponseBody}: this one carries no
 * {@code responseCount} and no {@code sentTo}. How many other people answered is the
 * creator's business, and a backer's client should not have to be trusted to hide it.
 *
 * @param open whether they may still answer or change it, computed against the clock at
 *     the moment of the read — so a client disables a form rather than letting somebody
 *     type into one that will refuse them
 * @param answers what they have already said, empty when they have not answered
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BackerSurveyBody(
        UUID surveyId,
        UUID projectId,
        UUID pledgeId,
        String title,
        String message,
        Instant respondBy,
        boolean open,
        boolean answered,
        Instant submittedAt,
        List<SurveyQuestionBody> questions,
        List<AnswerBody> answers) {

    public static BackerSurveyBody of(BackerSurvey survey) {
        return new BackerSurveyBody(
                survey.survey().getId(),
                survey.survey().getProjectId(),
                survey.pledgeId(),
                survey.survey().getTitle(),
                survey.survey().getMessage(),
                survey.survey().getRespondBy(),
                survey.open(),
                survey.isAnswered(),
                survey.isAnswered() ? survey.response().getSubmittedAt() : null,
                survey.questions().stream().map(SurveyQuestionBody::of).toList(),
                survey.answers().stream().map(AnswerBody::of).toList());
    }
}
