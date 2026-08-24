package az.ideanest.platform.api;

import az.ideanest.staff.api.StaffRefusals;
import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AD-12's and AD-16's refusals — issues #312 and #316.
 *
 * <p>One advice over both controllers because both refuse in exactly the two ways and in
 * no others: neither has a resource that can be missing — a flag is created by the same
 * {@code PUT} that would have found it, and the health screen reads whatever is there.
 */
@RestControllerAdvice(assignableTypes = {FeatureFlagController.class, SystemHealthController.class})
public class PlatformExceptionHandler {

    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        return StaffRefusals.notStaff(exception);
    }

    @ExceptionHandler(InsufficientStaffCapabilityException.class)
    public ProblemDetail handleInsufficient(InsufficientStaffCapabilityException exception) {
        return StaffRefusals.insufficient(exception);
    }
}
