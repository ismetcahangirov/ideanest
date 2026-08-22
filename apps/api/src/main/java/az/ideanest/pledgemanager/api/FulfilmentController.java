package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.FulfilmentImport;
import az.ideanest.pledgemanager.application.FulfilmentService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.8's PM-20 to PM-22 over HTTP.
 *
 * <p>Three endpoints, two of them §10.2's and one added: the import and the backer's
 * list are named there, and {@code GET /v1/projects/{id}/fulfilments} is not.
 * <strong>It is required by PM-22</strong>, whose actor column says "both" — an import
 * a creator cannot read back is a write-only endpoint, and the first thing anybody does
 * after uploading four thousand rows is look at what landed. §10.2 is amended in the
 * same change rather than left describing an API that is not the API.
 *
 * <p>Every response is {@code no-store}. The creator's list is what several thousand
 * backers were sent and where, and the backer's own list is where their parcel is;
 * neither belongs in a shared cache or on a disk after somebody signs out.
 */
@RestController
public class FulfilmentController {

    private final FulfilmentService fulfilments;

    public FulfilmentController(FulfilmentService fulfilments) {
        this.fulfilments = fulfilments;
    }

    /**
     * PM-20: applies a tracking file.
     *
     * <p><strong>{@code text/csv} rather than multipart.</strong> The body is one
     * document and nothing else, it is the same media type §4.7's CD-11 export
     * produces, and a multipart envelope would add a part name to a request that has
     * one part. A creator's tooling can therefore send back exactly the file it
     * received, with two more columns filled in.
     *
     * <p>200 rather than 202: the parcels are written before this returns, so a client
     * that reads the list next sees them. And 200 rather than 207 for a file with bad
     * rows — the request succeeded, and what happened to each row is in the body, which
     * {@link FulfilmentImportResponse} argues.
     */
    @PostMapping(
            value = "/v1/projects/{projectId}/fulfilments/import",
            consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ResponseEntity<FulfilmentImportResponse> importTracking(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId, @RequestBody String document) {

        FulfilmentImport result = fulfilments.importTracking(projectId, callerOf(accessToken), document);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(FulfilmentImportResponse.of(result));
    }

    /**
     * PM-22, the creator's half: every parcel on the campaign, with the counts.
     *
     * <p>Unpaged. It is a fulfilment working list rather than a screen a visitor loads,
     * and a creator cross-referencing a carrier's spreadsheet against six pages of
     * results is a creator who exports the file instead.
     */
    @GetMapping("/v1/projects/{projectId}/fulfilments")
    public ResponseEntity<FulfilmentListResponse> ofCampaign(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        UUID caller = callerOf(accessToken);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(FulfilmentListResponse.of(
                        fulfilments.ofCampaign(projectId, caller), fulfilments.progressOf(projectId, caller)));
    }

    /**
     * PM-21: where the caller's own rewards are.
     *
     * <p>Across every campaign, like {@code GET /v1/me/surveys}, and built from the
     * caller's backings rather than from a stored recipient list. A backer has one
     * screen for "where are my rewards" and it would be one request per campaign
     * otherwise.
     */
    @GetMapping("/v1/me/fulfilments")
    public ResponseEntity<BackerFulfilmentResponse> mine(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(BackerFulfilmentResponse.of(fulfilments.ofBacker(callerOf(accessToken))));
    }

    /** The account making the request, from our own signature and never from the body. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
