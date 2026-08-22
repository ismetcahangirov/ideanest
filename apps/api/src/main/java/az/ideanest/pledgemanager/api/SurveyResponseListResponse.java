package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.SurveyResponsePage;
import az.ideanest.pledgemanager.domain.SurveyAnswer;
import az.ideanest.pledgemanager.domain.SurveyResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What came back, for the creator — §4.8's PM-05.
 *
 * <p>The questions travel with the page because a response is a list of answers keyed
 * by question identifier: a client that had to fetch the survey separately to render a
 * column heading would be one request away from rendering the wrong one.
 *
 * @param total how many responses exist, which is what a creator compares against how
 *     many the survey was sent to
 */
public record SurveyResponseListResponse(
        UUID surveyId,
        String title,
        Integer sentTo,
        long total,
        List<SurveyQuestionBody> questions,
        List<Entry> responses) {

    /**
     * One backer's answers.
     *
     * @param backerId who answered. Behind {@code VIEW_FINANCES}, which is the same
     *     capability the backer report needs and for the same reason: this names a
     *     person and what they told the campaign
     * @param submittedAt when they last changed it, which is what tells a creator
     *     whether an answer moved after they placed an order
     */
    public record Entry(UUID pledgeId, UUID backerId, Instant submittedAt, List<AnswerBody> answers) {
    }

    public static SurveyResponseListResponse of(SurveyResponsePage page) {
        List<Entry> entries = page.responses().stream()
                .map(response -> new Entry(
                        response.getPledgeId(),
                        response.getBackerId(),
                        response.getSubmittedAt(),
                        answersOf(page, response)))
                .toList();

        return new SurveyResponseListResponse(
                page.survey().getId(),
                page.survey().getTitle(),
                page.survey().getSentTo(),
                page.total(),
                page.questions().stream().map(SurveyQuestionBody::of).toList(),
                entries);
    }

    private static List<AnswerBody> answersOf(SurveyResponsePage page, SurveyResponse response) {
        return page.answers().getOrDefault(response.getId(), List.<SurveyAnswer>of()).stream()
                .map(AnswerBody::of)
                .toList();
    }
}
