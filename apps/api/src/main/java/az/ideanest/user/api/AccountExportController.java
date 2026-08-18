package az.ideanest.user.api;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.shared.ratelimit.RateLimitExceededException;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import az.ideanest.user.UserProperties;
import az.ideanest.user.application.AccountExport;
import az.ideanest.user.application.AccountExportService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A machine-readable copy of the signed-in account's data.
 *
 * <p>{@code /v1/me/export}, under the account it belongs to, following §10.2's
 * convention that account-scoped things live under {@code /v1/me}. A
 * {@code GET}, because it produces a document and changes nothing.
 *
 * <p><strong>An export endpoint is an exfiltration endpoint.</strong> It returns
 * everything we hold about a person in one response, which makes it the single
 * most valuable request an attacker with a stolen access token can make. It is
 * therefore rate limited per account, and the response is marked
 * {@code no-store} so that nothing between us and the client keeps a copy.
 *
 * <p><strong>It is also the privileged action that has no transaction to be part
 * of</strong>, which is why the record is written by
 * {@code AuditLog.recordIndependently} rather than by {@code record}: the export
 * changes nothing, so there is no commit to join. The row is written before the
 * response is built, deliberately. A record of an export the client never received
 * is an over-record and costs nothing; an export with no record is the gap #107
 * exists to close, and "when was this account copied, and from where" is a question
 * only this row can answer.
 */
@RestController
public class AccountExportController {

    private final AccountExportService exports;
    private final RateLimiter rateLimiter;
    private final AuditLog audit;
    private final UserProperties properties;

    public AccountExportController(
            AccountExportService exports, RateLimiter rateLimiter, AuditLog audit, UserProperties properties) {
        this.exports = exports;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.properties = properties;
    }

    @GetMapping("/v1/me/export")
    public ResponseEntity<AccountExport> export(@AuthenticationPrincipal Jwt accessToken) {
        UUID userId = UUID.fromString(accessToken.getSubject());

        UserProperties.RateLimit limits = properties.rateLimit();
        // Per account, not per address. One stolen token should be worth one
        // copy of the account, not a tap on it, and the account is the thing
        // being drained.
        enforce(rateLimiter.recordAttempt("account-export:" + userId, limits.exportsPerAccount(), limits.window()));

        return exports.exportFor(userId)
                .map(export -> {
                    // After the export was produced, so an account that is not there
                    // records nothing, and before the response leaves.
                    audit.recordIndependently(
                            AuditAction.ACCOUNT_EXPORTED,
                            userId,
                            AuditActor.user(userId),
                            AuditOutcome.SUCCEEDED,
                            null);
                    return export;
                })
                .map(export -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        // Named so that a browser saves a file rather than
                        // rendering a wall of JSON, and so the file on the disk
                        // afterwards says what it is.
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ideanest-account.json\"")
                        .body(export))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static void enforce(RateLimitDecision decision) {
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }
}
