package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerSegmentNameTakenException;
import az.ideanest.pledge.application.BackerSegmentNotFoundException;
import az.ideanest.pledge.application.InvalidBackerFilterException;
import az.ideanest.pledge.application.TooManyBackerSegmentsException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The backer report's failures, as RFC 9457 problem details.
 *
 * <p>Scoped to the two controllers it belongs to rather than applied globally, for
 * {@code AnalyticsExceptionHandler}'s reason: an advice that catches a broad type across
 * the whole service turns a bug somewhere else into a tidy 4xx and hides it.
 *
 * <p><strong>Nothing here catches {@link IllegalArgumentException}.</strong>
 * {@code CurrencyMismatchException} is one, and its own comment says it must surface as a
 * 500 because a client cannot fix it by sending something else. Every type below is named
 * exactly.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4.
 */
@RestControllerAdvice(assignableTypes = {BackerReportController.class, BackerSegmentController.class})
public class BackerReportExceptionHandler {

    /**
     * 404 for a campaign that does not exist and for one this caller has no part in,
     * identically.
     *
     * <p>The same answer the analytics and referral reports give, and with the strongest
     * version of the reason: a 403 here would confirm to somebody enumerating identifiers
     * that a campaign exists <em>and</em> that it has a backer list worth asking for.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That project does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /**
     * 403 for a collaborator who is party to the campaign without holding VIEW_FINANCES.
     *
     * <p>Not a 404: they were invited, they can already see the campaign, and there is
     * nothing left to hide from them.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("Your grant on this campaign does not include the backer report.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }

    /**
     * 400 for a filter the report cannot answer.
     *
     * <p>The detail is the exception's message, which names the rule that was broken and is
     * written for a person. Safe because the only thing that raises this type is what the
     * caller sent.
     */
    @ExceptionHandler(InvalidBackerFilterException.class)
    public ProblemDetail handleInvalidFilter(InvalidBackerFilterException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/backer-filter-invalid"));
        problem.setTitle("That is not a filter this report answers");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "BACKER_FILTER_INVALID");
        return problem;
    }

    /** 404 for a segment that is not on this campaign, whether or not it exists elsewhere. */
    @ExceptionHandler(BackerSegmentNotFoundException.class)
    public ProblemDetail handleSegmentNotFound(BackerSegmentNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/backer-segment-not-found"));
        problem.setTitle("No such segment");
        problem.setDetail("That segment is not saved against this campaign.");
        problem.setProperty("code", "BACKER_SEGMENT_NOT_FOUND");
        return problem;
    }

    /** 409 when the campaign already has a segment by that name, compared folded. */
    @ExceptionHandler(BackerSegmentNameTakenException.class)
    public ProblemDetail handleNameTaken(BackerSegmentNameTakenException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/backer-segment-name-taken"));
        problem.setTitle("That name is taken");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "BACKER_SEGMENT_NAME_TAKEN");
        return problem;
    }

    /** 409 when the campaign is already holding as many segments as the report will. */
    @ExceptionHandler(TooManyBackerSegmentsException.class)
    public ProblemDetail handleTooMany(TooManyBackerSegmentsException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/backer-segments-exhausted"));
        problem.setTitle("No room for another segment");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "BACKER_SEGMENTS_EXHAUSTED");
        return problem;
    }
}
