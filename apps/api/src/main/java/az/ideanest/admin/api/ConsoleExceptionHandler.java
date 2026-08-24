package az.ideanest.admin.api;

import az.ideanest.project.application.NotAModeratorException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The console's three read surfaces, when they refuse — AD-05 and AD-14.
 *
 * <p>Scoped to those three controllers rather than applied globally, for the reason every
 * other advice in the service gives: an advice that catches a broad type across the whole
 * application turns a bug somewhere else into a tidy 4xx and hides it. It is separate from
 * {@link AdminUserExceptionHandler} for the same reason in miniature — that one maps
 * {@link IllegalArgumentException} to a refusal about suspending an account, and these
 * endpoints have no account to suspend.
 *
 * <p>Every response carries a {@code code} as well as a status, per §10.4. The status tells
 * a client how to behave; the code tells it what happened.
 */
@RestControllerAdvice(
        assignableTypes = {AuditTrailController.class, PaymentLogController.class, LedgerController.class})
public class ConsoleExceptionHandler {

    /**
     * 403 for a caller who is signed in and is not platform staff.
     *
     * <p>The same body the rest of the platform's staff surfaces answer with, because it is
     * the same refusal from the same configured list. A client that already handles
     * {@code NOT_A_MODERATOR} should not need a fourth branch for these three routes, and
     * when epic #100 replaces the list with a role model they all change together.
     */
    @ExceptionHandler(NotAModeratorException.class)
    public ProblemDetail handleNotStaff(NotAModeratorException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://ideanest.az/problems/not-a-moderator"));
        problem.setTitle("Not a moderator");
        // Deliberately not the exception's message, which names the account.
        problem.setDetail("The administration console is read by platform staff.");
        problem.setProperty("code", "NOT_A_MODERATOR");
        return problem;
    }

    /**
     * 400 for a ledger account that is not one of §7.2's six.
     *
     * <p>A refusal rather than an empty page, and the distinction is the whole reason this
     * handler exists. {@code ?account=platform_fees} — the plural, which is the mistake
     * somebody actually makes — would otherwise return a page of nothing, and a page of
     * nothing on a ledger reads as "this account is empty" rather than "there is no such
     * account". One of those is a fact about the platform's money.
     *
     * <p>The message is {@link az.ideanest.ledger.application.LedgerAccount}'s own, which names the value and points at the
     * six. It quotes what the caller sent and nothing else, so there is nothing here for a
     * support ticket to leak.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleUnknownAccount(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/unknown-ledger-account"));
        problem.setTitle("No such ledger account");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "UNKNOWN_LEDGER_ACCOUNT");
        problem.setProperty("meta", Map.of("parameter", "account"));
        return problem;
    }
}
