package az.ideanest.pledge.api;

import az.ideanest.pledge.PledgeProperties;
import az.ideanest.pledge.application.BackerExport;
import az.ideanest.pledge.application.BackerExportService;
import az.ideanest.pledge.application.BackerFilter;
import az.ideanest.pledge.application.BackerReportService;
import az.ideanest.pledge.application.BackerSegmentService;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.2's three backer-report routes: {@code GET /backers}, {@code GET /backers/breakdown}
 * and {@code POST /backers/export}. §4.7's CD-10, CD-07, CD-08 and CD-11.
 *
 * <h2>Why these live in the pledge module and not in analytics</h2>
 *
 * <p>Because they are questions about pledges, and {@code ModuleBoundaryTests} decides
 * where a question about pledges may be answered. The analytics module reads
 * {@code referral_attributions}, which carries no backer, no tier and no destination by
 * construction — V24 spends a paragraph on why. Everything on this controller needs at
 * least one of the three.
 *
 * <h2>{@code Cache-Control: private, no-store} on all three</h2>
 *
 * <p>The referral report gives the general reason — a campaign's takings belong to the
 * account that asked for them — and the list has a sharper one: it is every backer's name
 * and email address, so a shared cache holding this body holds a campaign's mailing list.
 *
 * <h2>The export is the one rate-limited route here</h2>
 *
 * <p>Per account rather than per address, like every other limit on a request carrying a
 * token. What it bounds is not the work — a capped read is cheap — but how much personal
 * data one stolen collaborator token can take out per minute. The list beside it is bounded
 * by its page size and needs no separate budget.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}")
public class BackerReportController {

    private final BackerReportService report;
    private final BackerExportService exports;
    private final BackerSegmentService segments;
    private final RateLimiter rateLimiter;
    private final PledgeProperties properties;

    public BackerReportController(
            BackerReportService report,
            BackerExportService exports,
            BackerSegmentService segments,
            RateLimiter rateLimiter,
            PledgeProperties properties) {

        this.report = report;
        this.exports = exports;
        this.segments = segments;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * One page of the campaign's backers, newest first.
     *
     * <p>The filter arrives as a query string here rather than as a body, because this is a
     * read a creator arrives at by following a link and by pressing back — a screen whose
     * state cannot be a URL is one that loses its place on every refresh. The nested shape
     * the export takes is the same four axes; a list of repeated keys is what a query string
     * can carry, and that is all this route needs.
     *
     * @param segment a saved segment to apply instead of the loose parameters. When both are
     *     sent the segment wins, matching the export
     * @param cursor the previous page's last pledge identifier
     * @param size how many rows, clamped to the configured maximum
     */
    @GetMapping("/backers")
    public ResponseEntity<BackerListResponse> backers(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID segment,
            @RequestParam(required = false) List<PledgeState> state,
            @RequestParam(required = false) List<UUID> rewardTier,
            @RequestParam(required = false) List<String> country,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) Integer size) {

        UUID accountId = accountOf(accessToken);
        BackerFilter filter = segment == null
                ? BackerFilter.of(setOf(state), setOf(rewardTier), setOf(country), q)
                : segments.filterOf(projectId, accountId, segment);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(BackerListResponse.of(report.page(projectId, accountId, filter, cursor, size)));
    }

    /**
     * CD-07 and CD-08: the campaign's reward mix and its destinations.
     *
     * <p>Takes no filter, deliberately. See {@code BackerReportService}: the splits describe
     * the campaign, and a chart that moved when a search box changed would be read as the
     * campaign changing rather than the question.
     */
    @GetMapping("/backers/breakdown")
    public ResponseEntity<BackerBreakdownResponse> breakdown(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(BackerBreakdownResponse.of(report.breakdown(projectId, accountOf(accessToken))));
    }

    /**
     * CD-11: the report as a CSV file.
     *
     * <p>Answers {@code text/csv} rather than JSON carrying a string, so that a browser can
     * save it and a fulfilment partner can open it without a client in between. Two headers
     * carry what the body cannot say about itself:
     *
     * <ul>
     *   <li>{@code Content-Disposition} names the file, including the campaign and the day.
     *   <li><strong>{@code X-Export-Truncated}</strong> and {@code X-Export-Rows} report
     *       whether the row cap was reached. A truncated fulfilment list looks exactly like
     *       a complete one, and a client that cannot tell will show one as the other. They
     *       are headers rather than fields because the body is a file, and a CSV with a
     *       status line in it is a CSV that breaks every importer.
     * </ul>
     */
    @PostMapping("/backers/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody(required = false) ExportBackersRequest request) {

        UUID accountId = accountOf(accessToken);
        PledgeProperties.Report limits = properties.report();
        RateLimits.enforce(rateLimiter.recordAttempt(
                "backer-export:account:" + accountId, limits.exportsPerAccount(), limits.exportWindow()));

        ExportBackersRequest asked = request == null ? new ExportBackersRequest(null, null) : request;
        BackerExport export = exports.export(projectId, accountId, asked.toFilter(), asked.segmentId());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(export.filename()).build());
        headers.add("X-Export-Rows", String.valueOf(export.rows()));
        headers.add("X-Export-Truncated", String.valueOf(export.truncated()));

        return ResponseEntity.ok()
                .headers(headers)
                .cacheControl(CacheControl.noStore().cachePrivate())
                // The charset is on the content type as well as in the byte order mark: the
                // mark is for the spreadsheet that opens the saved file, and this is for the
                // client that receives it.
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }

    private static UUID accountOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }

    /** An absent query parameter and an empty list are the same filter. */
    private static <T> Set<T> setOf(List<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
