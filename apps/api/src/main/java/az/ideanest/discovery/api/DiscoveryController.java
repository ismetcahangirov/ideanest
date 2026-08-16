package az.ideanest.discovery.api;

import az.ideanest.discovery.application.DiscoveryQuery;
import az.ideanest.discovery.application.SearchService;
import az.ideanest.discovery.application.UnsupportedDiscoveryOptionException;
import az.ideanest.discovery.domain.DiscoveryCapability;
import az.ideanest.project.application.Taxonomy;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Browsing campaigns, and the counts beside them. §10.2's {@code # Discovery}.
 *
 * <p>Public and unauthenticated. There is nothing in either response that belongs to
 * a person: it is the same feed for everybody with the same filters, which is what
 * makes it cacheable at all. The one filter that would change that —
 * {@code showOnly=saved} — is refused today and carries a note about what has to
 * change here when it is not.
 *
 * <h2>Caching</h2>
 *
 * <p>§10.3 asks for {@code ETag} and {@code Cache-Control} on public reads, and
 * {@code CategoryController} is the pattern: a digest over the content, never
 * {@code hashCode()}, which is not stable across two instances of the service or one
 * instance after a restart.
 *
 * <p><strong>Sixty seconds, not an hour.</strong> The taxonomy changes when somebody
 * decides to add a category. A discovery feed changes when anybody anywhere pledges:
 * {@code pledged_amount} and {@code backers_count} move continuously, and they are on
 * the card. A minute is chosen as the longest a progress bar may be wrong in a
 * <em>list</em> — the project page is the authoritative surface and is not cached
 * this way — and as short enough that the number never looks stuck. What it buys is
 * the §20 budget: a thousand requests a second against a handful of popular filter
 * combinations collapses to a handful of origin requests a minute.
 *
 * <p>The instant the popularity sort decays from, and the instant {@code daysLeft} is
 * measured against, are both truncated to the same minute, so that two requests
 * inside one window produce the same bytes and the tag actually matches. Without that
 * the ETag on the busiest sort would revalidate to a 200 every single time and look
 * like it was working.
 *
 * <p><strong>The tag varies with everything the body varies with.</strong> The
 * filters are in the URL and so need no help; the language is in a header and does,
 * which is what {@code Vary} and the locale in the digest are for. The feed carries
 * no translated text <em>today</em> — a card is a title somebody wrote and some
 * numbers — and it is still varied, because a shared cache populated without
 * {@code Vary} cannot be corrected afterwards, and the first localised field on a
 * card would then be served in the wrong language to everybody whose request happened
 * to miss.
 */
@RestController
public class DiscoveryController {

    /** See the class comment. Deliberately the same window the popularity score is bucketed to. */
    static final long MAX_AGE_SECONDS = 60;

    private final SearchService search;

    public DiscoveryController(SearchService search) {
        this.search = search;
    }

    /**
     * A page of cards. D-04's cursor-paginated infinite scroll.
     *
     * @param parameters every query parameter, bound by {@link DiscoveryQueryBinder}
     *     rather than by Spring; see that class for why
     * @return {@code 304} when the caller already holds this page in this language
     */
    @GetMapping("/v1/discover")
    public ResponseEntity<DiscoveryResponses.Feed> discover(
            WebRequest request,
            HttpServletResponse response,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestParam MultiValueMap<String, String> parameters) {

        String locale = Taxonomy.localeFor(acceptLanguage);
        DiscoveryQuery query = requireSupported(DiscoveryQueryBinder.bind(parameters, locale));

        DiscoveryResponses.Feed feed = DiscoveryResponses.feed(search.search(query));

        // On the raw response, because the 304 below is written by checkNotModified
        // and never passes through a ResponseEntity.
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        String etag = PublicReads.etagOf(locale, PublicReads.canonical(feed));
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(MAX_AGE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .body(feed);
    }

    /**
     * The counts for the filter panel. D-10's live faceted counts.
     *
     * <p>{@code limit} and {@code cursor} are accepted and ignored: a count is over
     * everything the filter matches, not over one page of it, and refusing a client
     * that reused its feed query string verbatim would be pedantry.
     */
    @GetMapping("/v1/discover/facets")
    public ResponseEntity<DiscoveryResponses.Facets> facets(
            WebRequest request,
            HttpServletResponse response,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestParam MultiValueMap<String, String> parameters) {

        String locale = Taxonomy.localeFor(acceptLanguage);
        DiscoveryQuery query = requireSupported(DiscoveryQueryBinder.bind(parameters, locale));

        DiscoveryResponses.Facets facets = DiscoveryResponses.facets(search.facets(query));

        response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        String etag = PublicReads.etagOf(locale, PublicReads.canonical(facets));
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(MAX_AGE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .body(facets);
    }

    /**
     * Refuses a query that asks for something no implementation can do yet.
     *
     * <p>The comparison itself lives on
     * {@link UnsupportedDiscoveryOptionException#requireSupported}, because
     * {@link SearchController} has to make exactly the same one. The alternative to
     * refusing — accepting the parameter and dropping it — is what this whole
     * mechanism exists to prevent. See {@link DiscoveryCapability}.
     */
    private DiscoveryQuery requireSupported(DiscoveryQuery query) {
        UnsupportedDiscoveryOptionException.requireSupported(query.requiredCapabilities(), search.capabilities());
        return query;
    }
}
