package az.ideanest.project.api;

import az.ideanest.project.application.Taxonomy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The category tree, for anyone.
 *
 * <p>Public and unauthenticated: it is the same list the discovery navigation
 * shows, and there is nothing in it that belongs to a person. The campaign editor
 * is the caller that needs it today — a creator cannot be asked to pick a category
 * from a list nobody will send them.
 *
 * <p>See {@link Taxonomy} for why this lives in the project module and why both
 * names are returned rather than one.
 */
@RestController
public class CategoryController {

    /**
     * How long a client may hold the tree without asking again.
     *
     * <p>An hour. The taxonomy changes when the platform decides to add a
     * category, which is a deliberate act somebody plans, not an event anybody is
     * waiting on — and the revalidation below makes a stale hour cost one
     * conditional request rather than a wrong page.
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
     * @param subcategories nested rather than a second request, because a
     *     {@code <select>} that has to wait for a second round trip to populate its
     *     dependent field is a select that appears empty for a moment
     */
    public record CategoryResponse(
            String id, String slug, String nameAz, String nameEn, List<SubcategoryResponse> subcategories) {
    }

    public record SubcategoryResponse(String id, String slug, String nameAz, String nameEn) {
    }

    /**
     * @return {@code 304} when the caller already holds this exact tree
     */
    @GetMapping("/v1/categories")
    public ResponseEntity<List<CategoryResponse>> categories(WebRequest request) {
        List<CategoryResponse> tree = taxonomy.all().stream()
                .map(category -> new CategoryResponse(
                        category.id().toString(),
                        category.slug(),
                        category.nameAz(),
                        category.nameEn(),
                        category.subcategories().stream()
                                .map(subcategory -> new SubcategoryResponse(
                                        subcategory.id().toString(),
                                        subcategory.slug(),
                                        subcategory.nameAz(),
                                        subcategory.nameEn()))
                                .toList()))
                .toList();

        String etag = etagOf(tree);
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
     */
    private static String etagOf(List<CategoryResponse> tree) {
        StringBuilder canonical = new StringBuilder();
        for (CategoryResponse category : tree) {
            append(canonical, category.id(), category.slug(), category.nameAz(), category.nameEn());
            for (SubcategoryResponse subcategory : category.subcategories()) {
                append(canonical, subcategory.id(), subcategory.slug(), subcategory.nameAz(), subcategory.nameEn());
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

    private static void append(StringBuilder canonical, String... fields) {
        for (String field : fields) {
            // A separator that cannot appear in a uuid, a slug, or a name, so
            // "ab" + "c" and "a" + "bc" cannot produce the same input.
            canonical.append(field).append(FIELD_SEPARATOR);
        }
        canonical.append(ROW_SEPARATOR);
    }
}
