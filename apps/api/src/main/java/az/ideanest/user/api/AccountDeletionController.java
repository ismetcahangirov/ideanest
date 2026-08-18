package az.ideanest.user.api;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.shared.ratelimit.RateLimitExceededException;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import az.ideanest.user.UserProperties;
import az.ideanest.user.application.AccountDeletionService;
import az.ideanest.user.application.AccountDeletionService.DeletionSchedule;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing the signed-in account, and changing one's mind.
 *
 * <p>{@code /v1/me/deletion} rather than {@code DELETE /v1/me}, and the paths in
 * §10.2 are why: {@code /v1/me} is the account and {@code DELETE} on it would
 * mean the account is gone when the response arrives. It is not — a request
 * creates a pending deletion that lives for thirty days and can be withdrawn,
 * so the thing being created and destroyed is the <em>request</em>, and that is
 * what the path names. {@code POST} creates it; {@code DELETE} withdraws it.
 *
 * <p><strong>Both are audited from here, and here is not the best place.</strong>
 * Closing an account and withdrawing the closure are privileged actions (#107) and
 * the record is written by {@code AuditLog.recordIndependently} <em>after</em>
 * {@link AccountDeletionService} has committed — so a failure of the audit write
 * leaves the deletion scheduled and the row missing, which is exactly the gap this
 * package exists to close, narrowed to one statement and made loud (the request
 * answers 500) rather than silent. The version without the gap is one line inside
 * that service's own transaction, and it belongs to whoever next opens
 * {@code user.application}; it is left out of this change to keep the edit inside
 * one module's boundary.
 */
@RestController
@RequestMapping("/v1/me/deletion")
public class AccountDeletionController {

    private final AccountDeletionService deletions;
    private final RateLimiter rateLimiter;
    private final AuditLog audit;
    private final UserProperties properties;

    public AccountDeletionController(
            AccountDeletionService deletions,
            RateLimiter rateLimiter,
            AuditLog audit,
            UserProperties properties) {
        this.deletions = deletions;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * @param scheduledFor the date the account is told. Returned because a
     *     confirmation the user cannot check is not a confirmation
     */
    public record DeletionScheduledResponse(Instant requestedAt, Instant scheduledFor) {
    }

    /**
     * Schedules the deletion. 202, not 200: what has happened is that we have
     * accepted an instruction to be carried out in thirty days.
     */
    @PostMapping
    public ResponseEntity<DeletionScheduledResponse> request(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody DeleteAccountRequest request) {

        UUID userId = UUID.fromString(accessToken.getSubject());

        // This endpoint verifies a password, which makes it a password oracle
        // for whoever is holding a stolen access token. Bounded per account
        // rather than per address: the account is what is being attacked, and an
        // attacker with one token can come from anywhere.
        UserProperties.RateLimit limits = properties.rateLimit();
        enforce(rateLimiter.recordAttempt(
                "account-deletion:" + userId, limits.deletionAttemptsPerAccount(), limits.window()));

        return deletions
                .request(userId, request.password())
                .map(schedule -> {
                    // Only when a deletion was actually scheduled. An empty result is
                    // a token for an account that is no longer there, and a wrong
                    // password throws before this line — neither closed anything.
                    audit.recordIndependently(
                            AuditAction.ACCOUNT_DELETION_REQUESTED,
                            userId,
                            AuditActor.user(userId),
                            AuditOutcome.SUCCEEDED,
                            "anonymisation scheduled for " + schedule.scheduledFor());
                    return schedule;
                })
                .map(AccountDeletionController::toResponse)
                .map(body -> ResponseEntity.accepted().body(body))
                // A genuine token for an account that is no longer there. The
                // token is ours and the account is not, which is 404 rather
                // than 401 — the same answer GET /v1/me gives.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Withdraws a pending deletion. No password: cancelling is the safe
     * direction, and the person who requested the deletion needed the password
     * to do it, so requiring it here would only obstruct the victim of a
     * deletion they did not ask for.
     */
    @DeleteMapping
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt accessToken) {
        UUID userId = UUID.fromString(accessToken.getSubject());

        if (!deletions.cancel(userId)) {
            return ResponseEntity.notFound().build();
        }
        // Recorded even when nothing was pending, because the service treats that as
        // success and the caller cannot tell the two apart either. The alternative is
        // an audit row that depends on a state the response does not report, which is
        // a worse thing to reason about than one extra row.
        audit.recordIndependently(
                AuditAction.ACCOUNT_DELETION_CANCELLED,
                userId,
                AuditActor.user(userId),
                AuditOutcome.SUCCEEDED,
                null);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private static DeletionScheduledResponse toResponse(DeletionSchedule schedule) {
        return new DeletionScheduledResponse(schedule.requestedAt(), schedule.scheduledFor());
    }

    private static void enforce(RateLimitDecision decision) {
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }
}
