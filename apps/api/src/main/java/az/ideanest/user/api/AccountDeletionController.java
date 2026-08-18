package az.ideanest.user.api;

import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
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
 */
@RestController
@RequestMapping("/v1/me/deletion")
public class AccountDeletionController {

    private final AccountDeletionService deletions;
    private final RateLimiter rateLimiter;
    private final UserProperties properties;

    public AccountDeletionController(
            AccountDeletionService deletions, RateLimiter rateLimiter, UserProperties properties) {
        this.deletions = deletions;
        this.rateLimiter = rateLimiter;
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
        RateLimits.enforce(rateLimiter.recordAttempt(
                "account-deletion:" + userId, limits.deletionAttemptsPerAccount(), limits.window()));

        return deletions
                .request(userId, request.password())
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

        return deletions.cancel(userId)
                ? ResponseEntity.status(HttpStatus.NO_CONTENT).build()
                : ResponseEntity.notFound().build();
    }

    private static DeletionScheduledResponse toResponse(DeletionSchedule schedule) {
        return new DeletionScheduledResponse(schedule.requestedAt(), schedule.scheduledFor());
    }
}
