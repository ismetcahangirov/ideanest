package az.ideanest.pledgemanager.application;

import java.time.Instant;
import java.util.UUID;

/**
 * §8.3's outbox payload for PM-24: one backer was reminded about one survey.
 *
 * <p><strong>Per recipient, unlike {@link SurveySentEvent}.</strong> The send is one
 * event about a campaign and the notification module fans it out; a reminder is already
 * the result of a fan-out — the job worked out exactly who has not answered — and
 * re-resolving that audience at delivery would chase the people who answered in the
 * meantime.
 *
 * <p>Which makes the events larger in number and each one smaller, and it is what lets
 * {@code survey_nudges} be the claim: one row, one event, one message, in one
 * transaction. An event carrying a list of recipients could not be, because a partial
 * delivery would have nothing to record.
 *
 * @param attempt which reminder this is, so that the third one can read differently
 *     from the first if the copy ever wants it to — and so that a support conversation
 *     about "I have had four of these" has something to check
 */
public record SurveyNudgedEvent(
        UUID surveyId,
        UUID projectId,
        UUID pledgeId,
        UUID backerId,
        String title,
        Instant respondBy,
        int attempt,
        Instant sentAt) {

    /** The aggregate is the campaign, as it is for every event about a survey. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "survey.nudged";
}
