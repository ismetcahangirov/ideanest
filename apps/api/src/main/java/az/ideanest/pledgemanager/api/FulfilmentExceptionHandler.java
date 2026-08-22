package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.FulfilmentImportRejectedException;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectNotFoundException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The fulfilment endpoints' failures, as RFC 9457 problem details.
 *
 * <p>Only three, because the fourth kind — a row this import will not apply — is
 * deliberately not an error at all. Those come back inside a 200 with the line number
 * and a code per row, which {@link FulfilmentImportResponse} argues: a file with three
 * bad rows out of four thousand is a successful import with three things for the
 * creator to fix, and a 400 would discard the other three thousand nine hundred and
 * ninety-seven.
 */
@RestControllerAdvice(assignableTypes = FulfilmentController.class)
public class FulfilmentExceptionHandler {

    /**
     * 400 for a document that cannot be read as a tracking file at all.
     *
     * <p>The code distinguishes the three cases, because they need three different
     * corrections: an empty file, a file with no {@code pledge_id} column, and a file
     * with a header and nothing under it.
     */
    @ExceptionHandler(FulfilmentImportRejectedException.class)
    public ProblemDetail handleRejectedImport(FulfilmentImportRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/fulfilment-import-rejected"));
        problem.setTitle("The tracking file could not be read");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", exception.code());
        problem.setProperty("meta", Map.of("expectedColumns", "pledge_id, status, carrier, tracking_number, tracking_url"));
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

    /** 403 for a collaborator whose grant does not include {@code VIEW_FINANCES}. */
    @ExceptionHandler(CapabilityNotGrantedException.class)
    public ProblemDetail handleCapabilityNotGranted(CapabilityNotGrantedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/capability-not-granted"));
        problem.setTitle("Not permitted");
        problem.setDetail("You do not have permission to manage this campaign's fulfilment.");
        problem.setProperty("code", "CAPABILITY_NOT_GRANTED");
        return problem;
    }
}
