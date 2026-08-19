package az.ideanest.project.api;

import az.ideanest.project.application.CampaignDashboardService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.2's {@code GET /v1/projects/{id}/dashboard} — §4.7's CD-01, and #93.
 *
 * <p>The first of the five routes §10.2 lists under Dashboard. The other four are
 * elsewhere and stay there: {@code /analytics} and {@code /referrers} are the analytics
 * module's, {@code /backers} is #97's, and {@code /finance} is #99's. They share a URL
 * prefix because they share a screen, not because they share an owner — this one reads
 * {@code projects} and belongs to the module that owns that table.
 *
 * <p><strong>Authorisation is one layer in</strong>, in {@code CampaignDashboardService},
 * because the capability check and the read have to be one transaction and the
 * transaction is the service's. {@code SecurityConfiguration} establishes only that there
 * is an authenticated account.
 */
@RestController
public class DashboardController {

    private final CampaignDashboardService dashboards;

    public DashboardController(CampaignDashboardService dashboards) {
        this.dashboards = dashboards;
    }

    /**
     * The campaign's live totals, its progress, and the two instants a countdown needs.
     *
     * <p><strong>{@code no-store}, and not a short {@code max-age}.</strong> Three
     * reasons, and the first is the one that decides it:
     *
     * <ul>
     *   <li>The body contains {@code serverTime}, and the entire point of that field is
     *       that it was true when it was sent. A cached copy is a clock that has stopped,
     *       handed to a client that will trust it to calibrate its own.
     *   <li>It is one creator's view of their own money, behind a bearer token. A shared
     *       cache holding it is a category of accident worth simply not having.
     *   <li>The figures move on every pledge, which on the campaigns that matter is
     *       continuously.
     * </ul>
     */
    @GetMapping(path = "/v1/projects/{id}/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DashboardResponse> dashboard(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {

        DashboardResponse body = DashboardResponse.of(dashboards.dashboard(id, callerOf(accessToken)));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /** The authenticated caller, from the token's subject and never from the request. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
