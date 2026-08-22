package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.AnswerInvalidException;
import az.ideanest.pledgemanager.application.PledgeNotBackedException;
import az.ideanest.pledgemanager.application.SurveyAlreadySentException;
import az.ideanest.pledgemanager.application.SurveyHasNoQuestionsException;
import az.ideanest.pledgemanager.application.SurveyNotFoundException;
import az.ideanest.pledgemanager.application.SurveyNotOpenException;
import az.ideanest.pledgemanager.application.TooManyQuestionsException;
import az.ideanest.pledgemanager.domain.SurveyContentInvalidException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The survey endpoints' failures, as RFC 9457 problem details.
 *
 * <p>Covers both controllers, unlike the addresses' advice, because the two share every
 * exception on this list — a backer and a creator both meet "no such survey" and "it is
 * not open". What they do not share is the projection, which is why the controllers are
 * separate and this is not.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4: the status
 * says how to behave and the code says what happened. Four different 409s that could
 * not be told apart would force clients to match on prose.
 */
@RestControllerAdvice(assignableTypes = {SurveyController.class, BackerSurveyController.class})
public class SurveyExceptionHandler {

    /** 404 for a survey that does not exist and for one on a campaign this caller cannot see. */
    @ExceptionHandler(SurveyNotFoundException.class)
    public ProblemDetail handleNotFound(SurveyNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/survey-not-found"));
        problem.setTitle("No such survey");
        problem.setDetail("That survey does not exist.");
        problem.setProperty("code", "SURVEY_NOT_FOUND");
        return problem;
    }

    /** 404 for a pledge that is not this caller's, does not exist, or is not a backing. */
    @ExceptionHandler(PledgeNotBackedException.class)
    public ProblemDetail handlePledgeNotBacked(PledgeNotBackedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/pledge-not-found"));
        problem.setTitle("No such pledge");
        problem.setDetail("That pledge does not exist.");
        problem.setProperty("code", "PLEDGE_NOT_FOUND");
        return problem;
    }

    /** 400 naming the control the builder can highlight. */
    @ExceptionHandler(SurveyContentInvalidException.class)
    public ProblemDetail handleInvalidContent(SurveyContentInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/survey-invalid"));
        problem.setTitle("Invalid survey");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "SURVEY_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field()));
        return problem;
    }

    /** 400 for a submission that answers one question twice. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleMalformedSubmission(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/survey-answer-invalid"));
        problem.setTitle("Invalid answers");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "SURVEY_ANSWER_INVALID");
        return problem;
    }

    /**
     * 409 for editing, deleting or re-sending a survey that has gone out.
     *
     * <p>Not a 403: the caller is permitted to do this to a draft, and what changed is
     * the state of the survey.
     */
    @ExceptionHandler(SurveyAlreadySentException.class)
    public ProblemDetail handleAlreadySent(SurveyAlreadySentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/survey-already-sent"));
        problem.setTitle("Survey already sent");
        problem.setDetail("This survey has gone out. Its questions cannot change, and it cannot be sent again"
                + " or deleted. The covering note and the deadline can still be edited.");
        problem.setProperty("code", "SURVEY_ALREADY_SENT");
        return problem;
    }

    /**
     * 409 for answering a survey that is not accepting answers.
     *
     * <p>The meta distinguishes the two causes, because they are different sentences to
     * a backer: it was never sent, or it closed on a date they deserve to be told.
     */
    @ExceptionHandler(SurveyNotOpenException.class)
    public ProblemDetail handleNotOpen(SurveyNotOpenException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/survey-not-open"));
        problem.setTitle("Survey closed");
        problem.setDetail(exception.wasSent()
                ? "This survey closed and is no longer accepting answers."
                : "This survey has not been sent yet.");
        problem.setProperty("code", "SURVEY_NOT_OPEN");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sent", exception.wasSent());
        meta.put("respondBy", exception.respondBy());
        problem.setProperty("meta", meta);
        return problem;
    }

    /** 422 for sending a survey that asks nothing. */
    @ExceptionHandler(SurveyHasNoQuestionsException.class)
    public ProblemDetail handleNoQuestions(SurveyHasNoQuestionsException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/survey-has-no-questions"));
        problem.setTitle("Nothing to ask");
        problem.setDetail("Add at least one question before sending this survey.");
        problem.setProperty("code", "SURVEY_HAS_NO_QUESTIONS");
        return problem;
    }

    /** 422, naming both numbers so a client can warn before the creator types the next one. */
    @ExceptionHandler(TooManyQuestionsException.class)
    public ProblemDetail handleTooManyQuestions(TooManyQuestionsException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/survey-too-many-questions"));
        problem.setTitle("Too many questions");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "SURVEY_TOO_MANY_QUESTIONS");
        problem.setProperty("meta", Map.of("limit", exception.limit(), "requested", exception.requested()));
        return problem;
    }

    /** 422 naming the question, so the form can highlight it. */
    @ExceptionHandler(AnswerInvalidException.class)
    public ProblemDetail handleInvalidAnswer(AnswerInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/survey-answer-invalid"));
        problem.setTitle("Invalid answer");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "SURVEY_ANSWER_INVALID");
        problem.setProperty("meta", Map.of("questionId", exception.questionId().toString()));
        return problem;
    }

    /** 404 for a campaign that does not exist, and for one this caller has no part in. */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That campaign does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /** 403 for a collaborator whose grant does not include the capability the call needs. */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("You do not have permission to do that on this campaign.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }
}
