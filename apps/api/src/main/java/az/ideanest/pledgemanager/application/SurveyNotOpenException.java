package az.ideanest.pledgemanager.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The survey is not accepting answers — §4.8's PM-06.
 *
 * <p>Two causes, and the response distinguishes them because the two are different
 * things to say to a backer: it has not been sent yet, which nobody outside the
 * campaign should normally be able to reach at all, and the cut-off has passed, which
 * is an ordinary and disappointing fact that deserves the date.
 *
 * <p>409. The caller is permitted to answer this survey and the body is well-formed;
 * what changed is the state of the survey.
 */
public class SurveyNotOpenException extends RuntimeException {

    private final UUID surveyId;
    private final boolean sent;
    private final Instant respondBy;

    public SurveyNotOpenException(UUID surveyId, boolean sent, Instant respondBy) {
        super(sent
                ? "Survey " + surveyId + " closed at " + respondBy
                : "Survey " + surveyId + " has not been sent");
        this.surveyId = surveyId;
        this.sent = sent;
        this.respondBy = respondBy;
    }

    public UUID surveyId() {
        return surveyId;
    }

    /** False means it is still a draft, which is a different sentence from "it closed". */
    public boolean wasSent() {
        return sent;
    }

    /** When it closed, or null when it was never sent. */
    public Instant respondBy() {
        return respondBy;
    }
}
