package az.ideanest.payout.api;

import az.ideanest.payout.application.CampaignFinanceService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/projects/{projectId}/finance}: §4.7's CD-16. Issue #99.
 *
 * <p><strong>A controller of its own rather than a method on {@code PayoutController}.</strong>
 * That one is §4.11's console: every endpoint on it is guarded by
 * {@code StaffCapability.VIEW_FINANCE} and its exception handler is written for a member of
 * staff. This is the campaign's own team, guarded by a project capability, and a stranger has
 * to get the same 404 a stranger gets everywhere else on a campaign. Two audiences, two
 * controllers, and neither can accidentally answer for the other.
 *
 * <p><strong>No exception handler here</strong>, deliberately. Everything this can raise —
 * {@code ProjectNotFoundException}, {@code CapabilityNotGrantedException} — is already mapped
 * by the project module's advice, which is where the 404-for-a-stranger rule is stated once.
 * A second mapping here would be a second place for that rule to be got wrong.
 *
 * <p><strong>{@code Cache-Control: private, no-store}</strong>, for the reason
 * {@code AnalyticsController} gives and with more force: this is a campaign's money. A shared
 * cache holding this body is a shared cache able to serve it to somebody else.
 */
@RestController
public class CampaignFinanceController {

    private final CampaignFinanceService finances;

    public CampaignFinanceController(CampaignFinanceService finances) {
        this.finances = finances;
    }

    /**
     * What this campaign has taken, what came off it, and what is left.
     *
     * <p>Who may read it is {@code ProjectAuthorisation}'s decision and is made one layer in,
     * where every other "may this account act on this campaign" is made. A stranger gets a 404
     * and a collaborator without {@code VIEW_FINANCES} gets a 403.
     */
    @GetMapping("/v1/projects/{projectId}/finance")
    public ResponseEntity<CampaignFinanceResponse> finance(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(CampaignFinanceResponse.of(
                        finances.of(projectId, UUID.fromString(accessToken.getSubject()))));
    }
}
