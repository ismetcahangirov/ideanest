package az.ideanest.obligation.api;

import az.ideanest.obligation.application.UpdateObligations;
import az.ideanest.user.application.UserAccounts;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * §5.5's clock, as anybody may read it — issue #437, surfaced by #439.
 *
 * <h2>Public, because that is the entire mechanism</h2>
 *
 * <p>§22.3 names "the creator's project history visible" as the consequence that makes an
 * obligation real, and the person it is meant to inform is the one deciding whether to back this
 * creator again — who has not signed in yet. Putting it behind authentication would leave the fact
 * visible to everybody except the audience it exists for.
 *
 * <p>It is a fact and not a sanction: the dates are on the response so a reader can check the
 * state rather than take it. Nothing here says a creator is untrustworthy; it says when they last
 * posted.
 *
 * <h2>Cacheable, briefly</h2>
 *
 * <p>Nothing in either answer belongs to a person and both change at most daily, so a shared cache
 * is correct. Five minutes rather than an hour: unlike a legal document, this is a countdown, and
 * the moment worth being right about is the one where a creator posts an update and their own
 * campaign page is still calling them late.
 */
@RestController
public class ObligationController {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final UpdateObligations obligations;
    private final UserAccounts accounts;

    public ObligationController(UpdateObligations obligations, UserAccounts accounts) {
        this.obligations = obligations;
        this.accounts = accounts;
    }

    /**
     * One campaign's obligation.
     *
     * <p><strong>204 and not 404 when there is none</strong>, which is the ordinary case: a
     * campaign that is live, was unsuccessful, or closed before this mechanism existed has no
     * clock, and none of those is an error the page should draw. A 404 would have the campaign
     * page rendering a failure state for a campaign that is perfectly fine.
     */
    @GetMapping(path = "/v1/projects/{projectId}/update-obligation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObligationResponses.Obligation> forProject(@PathVariable UUID projectId) {
        return obligations
                .forProject(projectId)
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                        .body(ObligationResponses.Obligation.of(view)))
                .orElseGet(() -> ResponseEntity.noContent()
                        .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                        .build());
    }

    /**
     * Every campaign this creator has run since the clock existed, newest first.
     *
     * <p>§22.3's project history. An empty list for a creator with no closed campaigns rather than
     * a 404: "this person has never run a campaign that succeeded" is an answer, and it is the one
     * a profile needs in order to say so.
     */
    @GetMapping(path = "/v1/creators/{creatorId}/update-obligations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObligationResponses.CreatorHistory> forCreator(@PathVariable UUID creatorId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(ObligationResponses.CreatorHistory.of(creatorId, obligations.forCreator(creatorId)));
    }

    /**
     * The same history, addressed the way the public profile is.
     *
     * <p><strong>Two addresses for one read, because the two callers know two different
     * names.</strong> The profile at {@code /u/{slug}} is reached by slug and §4.2's projection
     * deliberately carries no identifier — a public page that exposed one would be handing out
     * the key every other endpoint is addressed by. So a page that has just rendered somebody's
     * profile cannot call the route above, and inventing a lookup in the browser would be the
     * client doing the resolution the service already does.
     *
     * <p>{@code UserAccounts.findBySlug} is the resolution, and it is the port #90 added for
     * exactly this shape: following is done from a creator's page, and that page is reached by
     * slug.
     *
     * <p><strong>An unknown slug answers an empty history rather than 404</strong>, and that is
     * deliberate rather than lazy. {@code UserAccounts.findBySlug} treats a closed account as
     * not found, and §4.2 goes to some trouble to make an unknown slug, a closed account and a
     * private profile one indistinguishable answer — a 404 here would make this endpoint a way
     * to tell them apart from the outside, which is the leak the profile's own 404 exists to
     * prevent. A creator with no closed campaigns and a slug nobody holds both have nothing to
     * disclose, and both say so.
     */
    @GetMapping(path = "/v1/users/{slug}/update-obligations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObligationResponses.CreatorHistory> forCreatorSlug(@PathVariable String slug) {
        UUID creatorId = accounts.findBySlug(slug).map(account -> account.id()).orElse(null);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(
                        creatorId == null
                                ? ObligationResponses.CreatorHistory.empty()
                                : ObligationResponses.CreatorHistory.of(
                                        creatorId, obligations.forCreator(creatorId)));
    }
}
