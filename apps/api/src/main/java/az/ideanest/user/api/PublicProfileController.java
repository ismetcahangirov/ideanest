package az.ideanest.user.api;

import az.ideanest.user.application.PublicProfiles;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * A person's profile, to anybody — §4.2, and §10.2's {@code GET /v1/users/{slug}} (#274).
 *
 * <p><strong>A separate controller from {@link MeController}, for
 * {@code PublicProjectController}'s reason:</strong> everything on that one requires a
 * bearer token and describes the account to its owner, and this requires nothing at all
 * and describes it to a stranger. The two projections of {@code users} differ in what they
 * may say — one carries the address, the other must never — and keeping the difference in
 * the file layout is what stops a public endpoint inheriting an authenticated controller's
 * shape by being added next to one. {@link ProfileVisibilityController} is the third file
 * for the same reason: it writes.
 *
 * <p><strong>Addressed by the slug, which is the only public name an account has.</strong>
 * It is what {@code /v1/users/{slug}/follow} already takes, what the campaign page's
 * creator link is built from, and what §17.4 overwrites when somebody leaves. The account
 * identifier addresses nothing here on purpose — see {@link PublicProfileResponse}.
 *
 * <h2>Caching</h2>
 *
 * <p>{@code ETag} and {@code Cache-Control: public, no-cache}, which is §10.3's pair with
 * the freshness deliberately given up.
 *
 * <p>"Keep this body and ask before you use it again." A profile changes rarely, so almost
 * every revalidation is a 304 with no body, and the endpoint still costs one round trip
 * instead of one render. What a {@code max-age} would buy is not worth what it would cost:
 * the profile can be <em>withdrawn</em>, P-07 is the control that withdraws it, and a
 * shared cache holding this body for a minute is a page that stays readable for a minute
 * after its owner turned it off. A privacy switch whose effect is delayed by a cache is a
 * privacy switch that does not work, and the one time somebody uses it in a hurry is
 * exactly the time it has to.
 *
 * <p>{@code public} rather than {@code private}, because there is nothing in this body
 * that belongs to the person reading it: no session, no personalisation, not even a "do
 * you follow this account" flag — which is deliberate, since adding one would make the
 * response per-visitor and cost the shared revalidation that pays for the endpoint.
 */
@RestController
public class PublicProfileController {

    private final PublicProfiles profiles;

    public PublicProfileController(PublicProfiles profiles) {
        this.profiles = profiles;
    }

    /**
     * The profile at this address, or 404.
     *
     * <p>404 covers three different facts — no such slug, an account §17.4 has
     * anonymised, and an account whose owner chose {@code PRIVATE} — and
     * {@code ProfileNotFoundException} is where that decision is made and argued. A 403
     * for the third would confirm, to anybody who could guess a slug, that a particular
     * person has an account here and has chosen to hide it.
     *
     * @return {@code 304} when the caller already holds this profile
     */
    @GetMapping("/v1/users/{slug}")
    public ResponseEntity<PublicProfileResponse> profile(
            WebRequest request, HttpServletResponse response, @PathVariable String slug) {

        PublicProfileResponse body = PublicProfileResponse.of(profiles.requireVisible(slug));

        // On the raw response rather than on the ResponseEntity, for the reason
        // PublicProjectUpdateController gives: the 304 below is written by
        // checkNotModified and never passes through one, and a revalidation that dropped
        // the policy would leave a cache deciding for itself how long to hold a page its
        // owner may have withdrawn in the meantime.
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noCache().cachePublic().getHeaderValue());

        String etag = body.etag();
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }
}
