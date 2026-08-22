package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.Survey;
import java.time.Instant;
import java.util.UUID;

/**
 * §8.3's outbox payload for PM-04: a survey went out.
 *
 * <p><strong>Identifiers and counts, never the questions.</strong> The consumer reads
 * what it needs inside its own transaction, which is the rule {@code Outbox} states:
 * a copy of the survey in here would be a copy of creator content with a different
 * retention rule from the row it came from, multiplied by every redelivery.
 *
 * <p><strong>The audience is resolved twice and is allowed to differ.</strong> Once by
 * {@link SurveyService}, to freeze {@code sent_to} and tell the creator what they
 * reached; once by the notification module, to write the rows. Between the two, a
 * pledge can be cancelled or a refund can land. Making them agree would mean storing
 * the membership list — which is exactly what V31 refuses for segments, and for the
 * same reason: a stored list of who was asked is personal data with a second retention
 * rule in a table nothing sweeps.
 *
 * <p>{@code truncated} travels so the delivery side can log the same fact the creator
 * was told. A campaign above the ceiling has backers the platform decided not to ask,
 * and that should be visible from both ends.
 *
 * @param sentAt when the send happened, so that a redelivered event does not render as
 *     having happened now
 */
public record SurveySentEvent(
        UUID surveyId, UUID projectId, String title, Instant respondBy, int recipients, boolean truncated,
        Instant sentAt) {

    /** The aggregate this is about, which is the campaign — see {@code Outbox}. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "survey.sent";

    public static SurveySentEvent of(Survey survey, boolean truncated, Instant sentAt) {
        return new SurveySentEvent(
                survey.getId(),
                survey.getProjectId(),
                survey.getTitle(),
                survey.getRespondBy(),
                survey.getSentTo() == null ? 0 : survey.getSentTo(),
                truncated,
                sentAt);
    }
}
