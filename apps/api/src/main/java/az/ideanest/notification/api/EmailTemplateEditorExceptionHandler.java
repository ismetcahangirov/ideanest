package az.ideanest.notification.api;

import az.ideanest.notification.application.MissingTemplatePlaceholderException;
import az.ideanest.staff.api.StaffRefusals;
import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import java.net.URI;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AD-15's editing refusals — issue #315.
 *
 * <p>Separate from {@link EmailTemplateExceptionHandler}, which covers #86's preview and
 * test send. That advice is scoped to {@code EmailTemplateController} and carries two
 * refusals about sending mail — a relay that would not take a message, a type with no email
 * column — neither of which this controller can raise. An advice covering refusals only
 * some of its controllers can produce is one that has to be read to rule out, which is the
 * argument that class already makes for existing separately in the first place.
 */
@RestControllerAdvice(assignableTypes = EmailTemplateEditorController.class)
public class EmailTemplateEditorExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }

    /**
     * 422 when the edited copy drops something the shipped copy carries.
     *
     * <p>422 rather than 400: the request is well formed and the body is a perfectly good sentence. It
     * is refused for what it leaves out, which is a rule about content rather than shape.
     *
     * <p>The missing indices travel in {@code meta} so the screen can point at them —
     * telling somebody their copy is invalid without saying which placeholder is gone means
     * they diff two paragraphs by eye.
     */
    @ExceptionHandler(MissingTemplatePlaceholderException.class)
    public ProblemDetail handleMissingPlaceholder(MissingTemplatePlaceholderException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setType(URI.create("https://ideanest.az/problems/missing-template-placeholder"));
        problem.setTitle("The copy drops something it has to keep");
        problem.setDetail("This message carries facts the recipient needs. Keep every placeholder the "
                + "shipped copy uses.");
        problem.setProperty("code", "MISSING_TEMPLATE_PLACEHOLDER");
        problem.setProperty("meta", Map.of("missing", List.copyOf(exception.missing())));
        return problem;
    }
}
