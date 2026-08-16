package az.ideanest.discovery.api;

import az.ideanest.discovery.application.DiscoveryQuery;
import az.ideanest.discovery.application.SearchService;
import az.ideanest.discovery.application.UnsupportedDiscoveryOptionException;
import az.ideanest.discovery.domain.DiscoveryCapability;
import az.ideanest.project.application.Taxonomy;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
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
    private static final long MAX_AGE_SECONDS = 60;

    /** ASCII unit separator, for the digest. Cannot occur in a slug, a uuid, or a number. */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    /** ASCII record separator, so that a row boundary is not a field boundary. */
    private static final char ROW_SEPARATOR = (char) 0x1e;

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
        String etag = etagOf(locale, canonical(feed));
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
        String etag = etagOf(locale, canonical(facets));
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
     * <p>Every missing capability at once rather than the first: a client that sent
     * both a search term and {@code sort=relevance} should learn about both in one
     * round trip. The alternative to refusing — accepting the parameter and dropping
     * it — is what this whole mechanism exists to prevent. See
     * {@link DiscoveryCapability}.
     */
    private DiscoveryQuery requireSupported(DiscoveryQuery query) {
        Set<DiscoveryCapability> missing = EnumSet.noneOf(DiscoveryCapability.class);
        missing.addAll(query.requiredCapabilities());
        missing.removeAll(search.capabilities());
        if (!missing.isEmpty()) {
            throw new UnsupportedDiscoveryOptionException(missing);
        }
        return query;
    }

    /**
     * Everything in the feed that reaches the client, in a fixed order.
     *
     * <p>Every field, not a selection: a digest over a subset is a tag that fails to
     * change when the part it does not cover does, which is a client rendering last
     * minute's cards and never being told.
     */
    private static String canonical(DiscoveryResponses.Feed feed) {
        StringBuilder canonical = new StringBuilder();
        for (DiscoveryResponses.Card card : feed.items()) {
            append(canonical, card.id(), card.slug(), card.creatorSlug(), card.title(), card.state(), card.badge());
            append(
                    canonical,
                    card.creator().name(),
                    card.creator().slug(),
                    card.creator().avatarUrl());
            append(
                    canonical,
                    card.image() == null ? null : card.image().url(),
                    card.image() == null ? null : String.valueOf(card.image().width()),
                    card.image() == null ? null : String.valueOf(card.image().height()));
            append(
                    canonical,
                    card.goal() == null ? null : card.goal().amount().toPlainString(),
                    card.goal() == null ? null : card.goal().currency(),
                    card.pledged().amount().toPlainString(),
                    card.pledged().currency(),
                    card.completionPercent(),
                    String.valueOf(card.backersCount()),
                    String.valueOf(card.daysLeft()),
                    String.valueOf(card.launchedAt()),
                    String.valueOf(card.deadline()));
        }
        append(canonical, feed.nextCursor());
        return canonical.toString();
    }

    private static String canonical(DiscoveryResponses.Facets facets) {
        StringBuilder canonical = new StringBuilder();
        appendCounts(canonical, facets.status());
        for (DiscoveryResponses.CategoryCount category : facets.categories()) {
            append(canonical, category.slug(), category.name(), String.valueOf(category.count()));
            for (DiscoveryResponses.NamedCount subcategory : category.subcategories()) {
                append(canonical, subcategory.slug(), subcategory.name(), String.valueOf(subcategory.count()));
            }
        }
        for (DiscoveryResponses.NamedCount tag : facets.tags()) {
            append(canonical, tag.slug(), tag.name(), String.valueOf(tag.count()));
        }
        appendCounts(canonical, facets.completion());
        appendCounts(canonical, facets.goalAmount());
        appendCounts(canonical, facets.amountRaised());
        return canonical.toString();
    }

    private static void appendCounts(StringBuilder canonical, Iterable<DiscoveryResponses.ValueCount> counts) {
        for (DiscoveryResponses.ValueCount count : counts) {
            append(canonical, count.value(), String.valueOf(count.count()));
        }
    }

    /**
     * A tag derived from the content, deterministically.
     *
     * <p>Not {@code hashCode()}, for the reason {@code CategoryController} gives: a
     * tag has to mean the same thing to every instance of the service and to the same
     * instance after a restart, and nothing guarantees a record's hash is stable
     * across either. A digest over what is serialised is.
     *
     * <p>The locale is hashed first. Two languages of one response must not share a
     * tag, or a client that asked for one revalidates the other and is told 304.
     */
    private static String etagOf(String locale, String content) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest((locale + FIELD_SEPARATOR + content).getBytes(StandardCharsets.UTF_8));
        return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    }

    private static void append(StringBuilder canonical, String... fields) {
        for (String field : fields) {
            canonical.append(field).append(FIELD_SEPARATOR);
        }
        canonical.append(ROW_SEPARATOR);
    }
}
