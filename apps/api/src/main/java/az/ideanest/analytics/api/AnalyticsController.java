package az.ideanest.analytics.api;

import az.ideanest.analytics.application.ProjectAnalyticsService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/projects/{id}/analytics}: §10.2's dashboard endpoint, served from the
 * daily rollup.
 *
 * <p><strong>A controller of its own rather than a third method on
 * {@code ReferralController}.</strong> That class carries two endpoints with opposite
 * audiences and says why it keeps them together; this is a third audience-alike but a
 * different feature, and the practical reason is the advice: the referral controller's
 * exception handler maps every {@link IllegalArgumentException} to a 400, which is safe
 * there because the only thing that raises one is a source the client described. It
 * would not be safe here, where {@code CurrencyMismatchException} is also an
 * {@code IllegalArgumentException} and has to surface as a 500. Two controllers, two
 * advices, and neither can accidentally answer for the other.
 *
 * <p><strong>{@code Cache-Control: private, no-store}</strong>, for the reason the
 * referral report gives: a campaign's daily takings belong to the account that asked for
 * them, a shared cache holding this body is a shared cache able to serve it to somebody
 * else, and §10.3 asks for caching on public reads, which this is not.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}")
public class AnalyticsController {

    private final ProjectAnalyticsService analytics;

    public AnalyticsController(ProjectAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /**
     * A campaign's daily trend, over a range of calendar days.
     *
     * <p>Both parameters are optional and both are {@code YYYY-MM-DD} in the platform's
     * calendar — the same zone the rollup buckets by, which the body states so that a
     * client rendering it never has to guess. Omitting them asks for the last thirty
     * days ending today.
     *
     * <p>Who may read it is {@code ProjectAccess}' decision and is made one layer in,
     * where every other "may this account act on this campaign" is made. A stranger gets
     * a 404.
     *
     * @param from the first day, inclusive
     * @param to the last day, inclusive
     */
    @GetMapping("/analytics")
    public ResponseEntity<ProjectAnalyticsResponse> analytics(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ProjectAnalyticsResponse.of(
                        analytics.of(projectId, UUID.fromString(accessToken.getSubject()), from, to)));
    }
}
