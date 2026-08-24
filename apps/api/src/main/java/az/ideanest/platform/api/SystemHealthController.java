package az.ideanest.platform.api;

import az.ideanest.platform.application.SystemHealthService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-16 over HTTP — #316.
 *
 * <p><strong>Not {@code /actuator/health}, and the difference matters.</strong> That
 * endpoint answers a load balancer asking whether to send this instance traffic, in one
 * word, unauthenticated. This one answers a member of staff asking what is wrong, in
 * detail, and the detail includes job failure messages — which are the platform's own
 * stack traces and belong behind a capability check.
 *
 * <p><strong>{@code no-store}</strong>, like every response under this prefix, and here
 * for a second reason: a cached health screen is a health screen that says the platform
 * was fine, in the present tense. The measured instant travels in the body so a tab left
 * open cannot be mistaken for a live one.
 */
@RestController
@RequestMapping("/v1/admin/health")
public class SystemHealthController {

    private final SystemHealthService health;

    public SystemHealthController(SystemHealthService health) {
        this.health = health;
    }

    /** Queue depth, failed jobs and provider status, as of now. */
    @GetMapping
    public ResponseEntity<PlatformResponses.Health> snapshot(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PlatformResponses.Health.of(health.snapshot(callerOf(accessToken))));
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
