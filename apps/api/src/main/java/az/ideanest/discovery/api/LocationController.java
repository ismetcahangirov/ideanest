package az.ideanest.discovery.api;

import az.ideanest.discovery.application.Places;
import az.ideanest.project.application.Taxonomy;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * V16's gazetteer, for anyone, in their language — §4.3's location vocabulary (#276).
 *
 * <h2>Why a closed vocabulary needs a published index</h2>
 *
 * <p>{@code locations} has been readable through a filter since #47 and listable by nobody.
 * That was survivable while the only thing that consumed a city was {@code ?city=}, where
 * the client sends a slug it got from a facet count. It stopped being survivable when #276
 * gave §4.2's P-02 a location control: the profile editor has to offer a person the list
 * before they can choose from it, and the alternatives were a free-text box that produces a
 * 400 for every spelling but one, or eighteen names hard-coded in {@code apps/web} — which
 * is precisely what §4.3's "the taxonomy is data, not code" refuses.
 *
 * <h2>Public and unauthenticated</h2>
 *
 * <p>The same terms {@code CategoryController} and {@code CollectionController} are on, and
 * for the same reason: nothing in the response belongs to a person. It is reference data,
 * and the first caller happens to be an authenticated screen — which is an argument about
 * who asks first, not about who may.
 *
 * <h2>Caching</h2>
 *
 * <p><strong>An hour, the taxonomy's window rather than the feed's.</strong> The list
 * changes when somebody adds a city, which V16 describes as a privileged act that changes a
 * closed vocabulary every reader sees — a thing that is planned, not a thing that happens.
 * Nothing here moves when a pledge is made, so the sixty seconds {@code /v1/discover} needs
 * would be fifty-nine of them spent revalidating an answer nobody changed.
 *
 * <p>{@code Vary: Accept-Language} on the 200 <em>and</em> the 304, set on the raw response
 * because {@code checkNotModified} writes the 304 itself and it never passes through a
 * {@link ResponseEntity}. A cache that stored the Azerbaijani names and replayed them to a
 * client that asked for Russian would be worse than no cache, and it has to be on both
 * answers to prevent it.
 */
@RestController
public class LocationController {

    /** The taxonomy's window. See the class comment. */
    static final long MAX_AGE_HOURS = 1;

    private final Places places;

    public LocationController(Places places) {
        this.places = places;
    }

    /**
     * Every place a campaign or a person may say they are in.
     *
     * @param acceptLanguage optional; anything unsupported or unparseable resolves to
     *     Azerbaijani rather than failing the request, because this is a public cacheable
     *     read and a malformed header is the caller's bug rather than a reason to have no
     *     list at all
     * @return {@code 304} when the caller already holds this list in this language
     */
    @GetMapping("/v1/locations")
    public ResponseEntity<LocationResponses.LocationIndex> locations(
            WebRequest request,
            HttpServletResponse response,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {

        String locale = Taxonomy.localeFor(acceptLanguage);
        LocationResponses.LocationIndex body = LocationResponses.index(places.all(locale));

        response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        String etag = PublicReads.etagOf(locale, PublicReads.canonical(body));
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(MAX_AGE_HOURS, TimeUnit.HOURS).cachePublic())
                .body(body);
    }
}
