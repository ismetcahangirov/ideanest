package az.ideanest.staff.api;

import az.ideanest.staff.application.InsufficientStaffCapabilityException;
import az.ideanest.staff.application.NotAModeratorException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The two refusals every console endpoint can make, written once — #295.
 *
 * <h2>Why this is a helper and not a global {@code @RestControllerAdvice}</h2>
 *
 * <p>A single advice over {@code az.ideanest..api} would be one file instead of a dozen,
 * and it is deliberately not what this is. Every advice in this service is scoped to the
 * controllers it belongs to, because an advice that catches across the whole application
 * turns a bug somewhere else into a tidy 4xx and hides it — and there is a sharper reason
 * here: eight advices already handle {@link NotAModeratorException} for their own
 * controllers, so a second one handling the same type application-wide would leave which
 * of the two answers a matter of bean ordering. Two correct handlers whose precedence
 * nobody declared is a worse failure than a dozen registrations, because it changes with
 * the classpath.
 *
 * <p>So each module keeps its own advice and the <em>bodies</em> are shared. That is the
 * half that actually mattered: eleven near-identical {@code ProblemDetail} builders is
 * eleven places for the {@code code} to drift, and a console screen branches on the code.
 *
 * <h2>Neither body names the account</h2>
 *
 * <p>The exceptions carry the identifier so that the log can; the response does not. A
 * 403 that echoes an account identifier is one a caller can screenshot into a support
 * ticket.
 */
public final class StaffRefusals {

    private StaffRefusals() {
    }

    /**
     * 403 for a caller who is signed in and does not work here.
     *
     * <p>The same body and the same {@code code} the platform's staff surfaces have
     * answered with since before the role model, deliberately: a client that already
     * handles {@code NOT_A_MODERATOR} needs no new branch for the endpoints #295 adds.
     */
    public static ProblemDetail notStaff(NotAModeratorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/not-a-moderator"));
        problem.setTitle("Not a moderator");
        problem.setDetail("The administration console is read by platform staff.");
        problem.setProperty("code", "NOT_A_MODERATOR");
        return problem;
    }

    /**
     * 403 for a member of staff without this particular authority.
     *
     * <p><strong>A distinct code, and that is the whole of what #295 buys a reader.</strong>
     * Before the role model both cases were {@code NOT_A_MODERATOR}, so a moderator who
     * opened the refund console was told the same thing as a stranger — which reads as a
     * broken console rather than as a screen that is not theirs.
     *
     * <p>The missing capability travels in {@code meta} so the console can name it. That
     * is not a disclosure: the vocabulary is in the published contract and the console's
     * front door lists all of it already. What is not said is who holds it.
     */
    public static ProblemDetail insufficient(InsufficientStaffCapabilityException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/insufficient-staff-capability"));
        problem.setTitle("Not yours to do");
        problem.setDetail("This needs " + exception.required() + ", which your roles do not include.");
        problem.setProperty("code", "INSUFFICIENT_STAFF_CAPABILITY");
        problem.setProperty("meta", Map.of("capability", exception.required().name()));
        return problem;
    }
}
