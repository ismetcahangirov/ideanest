package az.ideanest.shared.api;

import az.ideanest.shared.ratelimit.RateLimitExceededException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors that are not specific to any one module, as RFC 9457 problem details.
 *
 * <p>One shape for every error, so a client parses failures the same way
 * wherever they came from.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(URI.create("https://ideanest.az/problems/rate-limited"));
        problem.setTitle("Too many requests");
        problem.setDetail("Too many attempts. Try again later.");

        long seconds = Math.max(1, exception.getRetryAfter().toSeconds());
        problem.setProperty("retryAfterSeconds", seconds);

        // A client that is told to wait can wait. Without the header it retries
        // immediately, and the limiter spends the rest of the window refusing.
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/invalid-request"));
        problem.setTitle("Invalid request");
        problem.setDetail("One or more fields are invalid.");

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            // The default message, not the rejected value: the rejected value of
            // a password field is a password, and this response is logged by
            // whatever sits in front of us.
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("errors", errors);

        return ResponseEntity.badRequest().body(problem);
    }
}
