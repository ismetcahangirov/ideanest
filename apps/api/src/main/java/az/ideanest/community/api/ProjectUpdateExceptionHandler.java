package az.ideanest.community.api;

import az.ideanest.community.application.UpdateNumberContendedException;
import az.ideanest.community.application.UpdateScheduleInvalidException;
import az.ideanest.community.domain.UpdateContentInvalidException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What the two update endpoints refuse, as RFC 9457 problem details (§10.4).
 *
 * <p>Scoped to those two controllers rather than declared globally, exactly as the
 * project module scopes its three advices: a second translation of
 * {@code ProjectNotFoundException} in front of a controller that already has one is two
 * bodies for one refusal, and the drift shows up as the same failure answering
 * differently depending on which endpoint produced it.
 *
 * <p>Two of these are the project module's exceptions, and they are translated here
 * rather than reused. {@code ProjectProblems} — the class that writes the 403 for the
 * project module's own controllers — is package-private in that module's {@code api}
 * package, and making it public so that this module could call it would be a dependency
 * between two {@code api} layers, which is the one direction the module boundary does
 * not permit at all.
 */
@RestControllerAdvice(assignableTypes = {ProjectUpdateController.class, PublicProjectUpdateController.class})
public class ProjectUpdateExceptionHandler {

    /** See {@link #handleContended}. The work is a database transaction; the honest expectation is milliseconds. */
    private static final long RETRY_CONTENDED_SECONDS = 1;

    /**
     * 404 for a campaign that does not exist, for one that is not publicly visible, and
     * for one this caller has no relationship to.
     *
     * <p>Deliberately the same answer for all three, as everywhere else in the platform.
     * A draft is an unreleased product; telling a caller apart from "not there" and "not
     * yours" would turn this endpoint into an oracle for what other people are
     * preparing, and it would do it from a path that needs no token at all.
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleNotFound(ProjectNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/project-not-found"));
        problem.setTitle("No such project");
        problem.setDetail("That project does not exist.");
        problem.setProperty("code", "PROJECT_NOT_FOUND");
        return problem;
    }

    /**
     * 403 for a collaborator who works on the campaign and may not publish to it.
     *
     * <p>Not a 404: they were invited and can already read the campaign, so there is
     * nothing left to hide from them. The body names what would have authorised the
     * request and what they hold, so the client can disable the control rather than let
     * somebody write an update and be refused at the end of it.
     */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted on this campaign");
        problem.setDetail("Your collaborator access on this campaign does not include that.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requiredAnyOf", exception.requiredAnyOf());
        meta.put("held", exception.held());
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 400 for a title or a body the platform will not store.
     *
     * <p>Names the field rather than reporting the whole request as invalid, for
     * {@code StoryDocumentInvalidException}'s reason: the message is shown beside the
     * input that caused it, and a creator who has just written eight hundred words
     * should not have to guess which of two fields the refusal is about.
     */
    @ExceptionHandler(UpdateContentInvalidException.class)
    public ProblemDetail handleContentInvalid(UpdateContentInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/update-content-invalid"));
        problem.setTitle("Invalid update");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "UPDATE_CONTENT_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field()));
        return problem;
    }

    /**
     * 400 for a {@code publishAt} the platform will not accept.
     *
     * <p>Carries the boundary that was missed where there is one, so the client can move
     * the date picker to it. All three refusals are "not then, from here onwards", and a
     * message without the "here" leaves the creator guessing.
     */
    @ExceptionHandler(UpdateScheduleInvalidException.class)
    public ProblemDetail handleScheduleInvalid(UpdateScheduleInvalidException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/update-schedule-invalid"));
        problem.setTitle("Invalid publication time");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "UPDATE_SCHEDULE_INVALID");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requested", exception.requested());
        // Null when the refusal is "too far away" rather than "too soon": there is no
        // earliest moment to offer, and a value invented for symmetry would point at one.
        meta.put("earliestAllowed", exception.earliestAllowed());
        problem.setProperty("meta", meta);
        return problem;
    }

    /**
     * 409 for the loser of a race to allocate a campaign's first update number.
     *
     * <p>Not a 500. Nothing was written, the request was well formed, and the correct
     * behaviour — send it again — is one the client can take without a person being
     * involved. {@code Retry-After} is set for the reason {@code ApiExceptionHandler}
     * gives about every other retryable refusal: a client that is told to wait waits,
     * and one that is not retries immediately.
     */
    @ExceptionHandler(UpdateNumberContendedException.class)
    public ResponseEntity<ProblemDetail> handleContended(UpdateNumberContendedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/update-number-contended"));
        problem.setTitle("Another update was published at the same moment");
        problem.setDetail("Two updates were published at once. Send this one again.");
        problem.setProperty("code", "UPDATE_NUMBER_CONTENDED");
        problem.setProperty("retryAfterSeconds", RETRY_CONTENDED_SECONDS);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_CONTENDED_SECONDS))
                .body(problem);
    }
}
