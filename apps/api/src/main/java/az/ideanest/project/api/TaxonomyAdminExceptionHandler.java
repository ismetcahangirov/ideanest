package az.ideanest.project.api;

import az.ideanest.project.application.TaxonomyNotFoundException;
import az.ideanest.project.application.TaxonomySlugTakenException;
import az.ideanest.staff.api.StaffRefusals;
import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AD-08's refusals — issue #309.
 *
 * <p>Scoped to {@link TaxonomyAdminController} rather than folded into
 * {@code ProjectExceptionHandler}: that advice is about campaigns and their state machine,
 * and a sentence about a category slug in front of somebody editing a campaign would be
 * the wrong answer to the wrong question.
 */
@RestControllerAdvice(assignableTypes = TaxonomyAdminController.class)
public class TaxonomyAdminExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }

    /** 404 for a category or subcategory identifier that names nothing. */
    @ExceptionHandler(TaxonomyNotFoundException.class)
    public ProblemDetail handleNotFound(TaxonomyNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/taxonomy-not-found"));
        problem.setTitle("No such entry");
        problem.setDetail("That category or subcategory no longer exists.");
        problem.setProperty("code", "TAXONOMY_NOT_FOUND");
        return problem;
    }

    /**
     * 409 when the handle is taken.
     *
     * <p>The slug travels in {@code meta} because it is the one field that cannot be
     * changed after the entry is created — so being told which value collided is what lets
     * somebody choose another before committing to it.
     */
    @ExceptionHandler(TaxonomySlugTakenException.class)
    public ProblemDetail handleSlugTaken(TaxonomySlugTakenException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://ideanest.az/problems/taxonomy-slug-taken"));
        problem.setTitle("Handle already in use");
        problem.setDetail("Another entry already uses that handle. Handles are permanent, so pick another.");
        problem.setProperty("code", "TAXONOMY_SLUG_TAKEN");
        problem.setProperty("meta", Map.of("slug", exception.slug()));
        return problem;
    }
}
