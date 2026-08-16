package az.ideanest.project.api;

import az.ideanest.project.application.Taxonomy;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The category tree, for anyone, in their language.
 *
 * <p>Public and unauthenticated: it is the same list the discovery navigation
 * shows, and there is nothing in it that belongs to a person. The campaign editor
 * is the caller that needs it today — a creator cannot be asked to pick a category
 * from a list nobody will send them.
 *
 * <p>Each taxon carries three things. {@code name} is resolved against the
 * request's {@code Accept-Language} with the {@code az} fallback, and is what a
 * client renders. {@code names} is every translation the taxonomy holds for it,
 * so a client with its own language switcher does not need a second request per
 * language. See {@link Taxonomy} for the resolution chain and why it lives there.
 *
 * <p><strong>{@code nameAz} and {@code nameEn} are interim.</strong> They are the
 * columns V6 created and V11 kept, returned for one more release because
 * {@code apps/web} and this service deploy separately and a web build that reads
 * them may still be in a browser cache when this one ships. They are removed —
 * from this response, from {@code Category}/{@code Subcategory}, and then from
 * the schema by the contract half of V11 — once no client reads them: concretely,
 * once a web release that reads only {@code name} has been live longer than the
 * bundle cache. Nothing new may start reading them.
 */
@RestController
public class CategoryController {

    /**
     * How long a client may hold the tree without asking again.
     *
     * <p>An hour. The taxonomy changes when the platform decides to add a
     * category or a language, which is a deliberate act somebody plans, not an
     * event anybody is waiting on — and the revalidation below makes a stale hour
     * cost one conditional request rather than a wrong page.
     */
    private static final long MAX_AGE_HOURS = 1;

    /** ASCII unit separator, for the digest below. */
    private static final char FIELD_SEPARATOR = '\u001f';

    /** ASCII record separator, so a row boundary is not a field boundary. */
    private static final char ROW_SEPARATOR = '\u001e';

    private final Taxonomy taxonomy;

    public CategoryController(Taxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /**
     * @param name the one to render; resolved locale, then {@code az}, then the slug
     * @param names every translation held for this taxon, keyed by locale code
     * @param nameAz interim; see the class comment
     * @param nameEn interim
     * @param subcategories nested rather than a second request, because a
     *     {@code <select>} that has to wait for a second round trip to populate its
     *     dependent field is a select that appears empty for a moment
     */
    public record CategoryResponse(
            String id,
            String slug,
            String name,
            Map<String, String> names,
            String nameAz,
            String nameEn,
            List<SubcategoryResponse> subcategories) {
    }

    public record SubcategoryResponse(
            String id, String slug, String name, Map<String, String> names, String nameAz, String nameEn) {
    }

    /**
     * @param acceptLanguage optional; anything unparseable or unsupported resolves
     *     to Azerbaijani rather than failing the request, because this is a public
     *     cacheable read and a malformed header is the client's bug
     * @return {@code 304} when the caller already holds this exact tree in this
     *     exact language
     */
    @GetMapping("/v1/categories")
    public ResponseEntity<List<CategoryResponse>> categories(
            WebRequest request,
            HttpServletResponse response,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {

        String locale = Taxonomy.localeFor(acceptLanguage);

        // Set on the raw response rather than on the ResponseEntity because the
        // 304 below is written by checkNotModified and never passes through one.
        // A cache that stored the Azerbaijani tree and replayed it to a client
        // that asked for English is precisely what this header prevents, and it
        // has to be on both answers to prevent it.
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);

        List<CategoryResponse> tree = taxonomy.all(locale).stream()
                .map(category -> new CategoryResponse(
                        category.id().toString(),
                        category.slug(),
                        category.name(),
                        category.names(),
                        category.nameAz(),
                        category.nameEn(),
                        category.subcategories().stream()
                                .map(subcategory -> new SubcategoryResponse(
                                        subcategory.id().toString(),
                                        subcategory.slug(),
                                        subcategory.name(),
                                        subcategory.names(),
                                        subcategory.nameAz(),
                                        subcategory.nameEn()))
                                .toList()))
                .toList();

        String etag = etagOf(locale, tree);
        // Sets 304 and the header itself when the tag matches. Returning null from
        // a handler after this is the documented way to say "already answered".
        if (request.checkNotModified(etag)) {
            return null;
        }

        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(MAX_AGE_HOURS, TimeUnit.HOURS).cachePublic())
                .body(tree);
    }

    /**
     * A tag derived from the content, deterministically.
     *
     * <p>Not {@code hashCode()}: a tag has to mean the same thing to every instance
     * of the service and to the same instance after a restart, and nothing
     * guarantees a record's hash is stable across either. A digest over the fields
     * that are serialised is.
     *
     * <p>The resolved locale is the first thing hashed, and every field that
     * reaches the client is hashed after it. Two languages of the same tree must
     * not share a tag: a shared one means a client that asked for English and
     * revalidated an Azerbaijani copy is told 304 and renders the wrong language
     * for an hour, which is worse than not caching at all. {@code Vary} tells a
     * shared cache the same thing; the tag is what makes the client's own
     * conditional request correct.
     */
    private static String etagOf(String locale, List<CategoryResponse> tree) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, locale);
        for (CategoryResponse category : tree) {
            append(canonical, category.id(), category.slug(), category.name(), category.nameAz(), category.nameEn());
            appendNames(canonical, category.names());
            for (SubcategoryResponse subcategory : category.subcategories()) {
                append(
                        canonical,
                        subcategory.id(),
                        subcategory.slug(),
                        subcategory.name(),
                        subcategory.nameAz(),
                        subcategory.nameEn());
                appendNames(canonical, subcategory.names());
            }
        }

        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));

        // Sixteen hex characters is 64 bits of a digest over a list of at most a
        // few hundred short strings. Quoted, and strong: the bytes either match
        // exactly or they do not.
        return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    }

    /**
     * The translations, iterated in the order the map holds them.
     *
     * <p>{@code Taxonomy} builds that map in the fixed order of
     * {@code SUPPORTED_LOCALES} precisely so this digest is stable — an immutable
     * map from {@code Map.copyOf} is salted per JVM run and would give two
     * replicas two tags for one tree.
     */
    private static void appendNames(StringBuilder canonical, Map<String, String> names) {
        for (Map.Entry<String, String> translation : names.entrySet()) {
            append(canonical, translation.getKey(), translation.getValue());
        }
    }

    private static void append(StringBuilder canonical, String... fields) {
        for (String field : fields) {
            // A separator that cannot appear in a uuid, a slug, or a name, so
            // "ab" + "c" and "a" + "bc" cannot produce the same input.
            canonical.append(field).append(FIELD_SEPARATOR);
        }
        canonical.append(ROW_SEPARATOR);
    }
}
