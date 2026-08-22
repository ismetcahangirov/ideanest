package az.ideanest.pledgemanager.application;

import java.util.UUID;

/**
 * The survey has gone out, so this is no longer available.
 *
 * <p>Raised by three operations: sending it again, deleting it, and changing its
 * questions. All three would break the same promise — that what a backer was asked is
 * what their answer is an answer to.
 *
 * <p>409. Nothing about the request is malformed and the caller is permitted to do
 * this to a draft; what has changed is the state of the survey.
 *
 * <p><strong>The note and the cut-off are still editable</strong>, and the refusal is
 * raised before anything is written, so a creator who tried to edit a sent survey does
 * not find its title changed and its questions not.
 */
public class SurveyAlreadySentException extends RuntimeException {

    private final UUID surveyId;

    public SurveyAlreadySentException(UUID surveyId) {
        super("Survey " + surveyId + " has already been sent");
        this.surveyId = surveyId;
    }

    public UUID surveyId() {
        return surveyId;
    }
}
