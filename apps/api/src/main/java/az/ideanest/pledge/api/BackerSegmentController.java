package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerSegment;
import az.ideanest.pledge.application.BackerSegmentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.7's CD-10, second half: the filters a campaign has saved and named.
 *
 * <p><strong>A controller of its own rather than four more methods on
 * {@code BackerReportController}.</strong> That one answers three read-shaped routes about
 * people; this one is CRUD over a small object of the campaign's own, and the two have
 * different failure modes — a name collision and a segment that is not there mean nothing
 * on the report, and a currency mismatch means nothing here. Keeping them apart keeps each
 * advice narrow, which is the argument {@code AnalyticsController} makes about sharing one
 * with the referral report.
 *
 * <p><strong>{@code PUT} and not {@code PATCH}</strong> for the replace: a segment is a
 * name and four axes that a creator re-picks in one interaction, and a partial update would
 * need a way to say "clear the country filter" that is distinguishable from "leave it
 * alone". {@code SaveBackerSegmentRequest} says the same thing from the other side.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/backer-segments")
public class BackerSegmentController {

    private final BackerSegmentService segments;

    public BackerSegmentController(BackerSegmentService segments) {
        this.segments = segments;
    }

    /**
     * Every segment on this campaign, newest first.
     *
     * <p>{@code no-store} like the report itself. A segment carries no personal data, but
     * it does carry the search terms a creator typed — which can be a backer's email
     * address — and a body that may contain one is not a body for a shared cache.
     */
    @GetMapping
    public ResponseEntity<List<BackerSegmentResponse>> segments(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(BackerSegmentResponse.of(segments.of(projectId, accountOf(accessToken))));
    }

    /** Saves a filter under a name. */
    @PostMapping
    public ResponseEntity<BackerSegmentResponse> save(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody SaveBackerSegmentRequest request) {

        BackerSegment segment =
                segments.save(projectId, accountOf(accessToken), request.name(), request.toFilter());

        return ResponseEntity.created(
                        URI.create("/v1/projects/" + projectId + "/backer-segments/" + segment.id()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(BackerSegmentResponse.of(segment));
    }

    /** Replaces a segment's name and filter. */
    @PutMapping("/{segmentId}")
    public ResponseEntity<BackerSegmentResponse> replace(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @PathVariable UUID segmentId,
            @Valid @RequestBody SaveBackerSegmentRequest request) {

        BackerSegment segment = segments.replace(
                projectId, accountOf(accessToken), segmentId, request.name(), request.toFilter());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(BackerSegmentResponse.of(segment));
    }

    /**
     * Forgets a segment.
     *
     * <p>204, and a 404 when it is not there — rather than the idempotent "204 either way".
     * A creator who deletes the same segment twice has a stale screen, and telling them so
     * is more useful than pretending the second attempt did something.
     */
    @DeleteMapping("/{segmentId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId, @PathVariable UUID segmentId) {

        segments.delete(projectId, accountOf(accessToken), segmentId);
        return ResponseEntity.noContent().build();
    }

    private static UUID accountOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
