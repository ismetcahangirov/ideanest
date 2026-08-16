package az.ideanest.discovery.api;

import az.ideanest.discovery.application.UnknownFilterValueException;
import az.ideanest.discovery.application.UnsupportedDiscoveryOptionException;
import az.ideanest.discovery.domain.DiscoveryCapability;
import az.ideanest.discovery.domain.InvalidCursorException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Discovery's own failures, as RFC 9457 problem details (§10.4).
 *
 * <p>Scoped to this module's controllers rather than applied globally, for the reason
 * {@code RewardExceptionHandler} gives: an advice that catches a broad type across the
 * whole service turns a bug somewhere else into a tidy 4xx and hides it. Both of them
 * are named, because {@code /v1/search} raises exactly the same three failures as
 * {@code /v1/discover} — it binds the same parameters and issues the same query — and
 * an advice that covered one would leave the other answering a bad cursor with a 500.
 *
 * <p>Every response carries a {@code code} as well as a status. All three failures
 * here are 400s and a client has to be able to tell them apart without matching on
 * prose — one means "start again from the first page", one means "stop sending this
 * parameter", and one means "fix the value".
 */
@RestControllerAdvice(assignableTypes = {DiscoveryController.class, SearchController.class})
public class DiscoveryExceptionHandler {

    /**
     * 400 for a query that asks for something no implementation can do yet.
     *
     * <p>Not 501, which would tell a client the service is incomplete and to try
     * another instance: every instance answers this the same way, and the request is
     * the thing that has to change. Not 200 with the option ignored, which is the
     * failure this exists to prevent.
     *
     * <p>{@code meta.unsupported} names the parameter and the issue that owns it, so
     * that a client developer is sent to the issue rather than to the source.
     */
    @ExceptionHandler(UnsupportedDiscoveryOptionException.class)
    public ProblemDetail handleUnsupported(UnsupportedDiscoveryOptionException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/discovery-option-unsupported"));
        problem.setTitle("Not available yet");
        problem.setDetail("This request asks for a discovery option that is not implemented yet.");
        problem.setProperty("code", "DISCOVERY_OPTION_UNSUPPORTED");

        List<Map<String, String>> unsupported = new ArrayList<>();
        for (DiscoveryCapability capability : exception.missing()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("parameter", capability.parameter());
            entry.put("needs", capability.owner());
            unsupported.add(entry);
        }
        problem.setProperty("meta", Map.of("unsupported", unsupported));
        return problem;
    }

    /**
     * 400 for a cursor that does not belong here.
     *
     * <p>Two codes, because a client fixes them differently.
     * {@code DISCOVERY_CURSOR_MISMATCH} means the filters or the sort changed while a
     * cursor was kept, and the fix is to drop the cursor when the query changes.
     * {@code DISCOVERY_CURSOR_INVALID} means the token is not one this service issued,
     * and the fix is to start from the first page.
     *
     * <p>Refused rather than tolerated. Ignoring a stale cursor and answering with
     * page one silently restarts an infinite scroll from the top; the client reads
     * that as "there is more" and appends, and the reader sees every card twice with
     * nothing anywhere reporting a problem.
     */
    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidCursorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(exception.isMismatch()
                ? "https://ideanest.az/problems/discovery-cursor-mismatch"
                : "https://ideanest.az/problems/discovery-cursor-invalid"));
        problem.setTitle(exception.isMismatch() ? "Cursor does not match this query" : "Invalid cursor");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", exception.isMismatch() ? "DISCOVERY_CURSOR_MISMATCH" : "DISCOVERY_CURSOR_INVALID");
        return problem;
    }

    /**
     * 400 for a value that is not one of the values a filter takes.
     *
     * <p>{@code meta.allowed} carries the whole vocabulary, so the fix is in the
     * response rather than in documentation the caller has to go and find. A slug that
     * names nothing is deliberately not this error — see
     * {@link UnknownFilterValueException}.
     */
    @ExceptionHandler(UnknownFilterValueException.class)
    public ProblemDetail handleUnknownValue(UnknownFilterValueException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/discovery-value-unknown"));
        problem.setTitle("Unknown filter value");
        problem.setDetail("'" + exception.value() + "' is not a value that " + exception.parameter() + " takes.");
        problem.setProperty("code", "DISCOVERY_VALUE_UNKNOWN");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("parameter", exception.parameter());
        meta.put("value", exception.value());
        meta.put("allowed", exception.allowed());
        problem.setProperty("meta", meta);
        return problem;
    }
}
