package az.ideanest.project.api;

import az.ideanest.project.application.TaxonomyAdministration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-08 over HTTP — issue #309.
 *
 * <h2>Two writes, and neither is a delete</h2>
 *
 * <p>{@code POST} adds, {@code PATCH} renames and reorders, {@code PUT} writes a
 * translation for one locale. There is no {@code DELETE}, and
 * {@code TaxonomyAdministration} has the argument: {@code projects.category_id} references
 * these rows, so a delete either fails on the constraint or takes campaigns with it, and
 * retiring a category needs a column V6 does not have.
 *
 * <p><strong>No endpoint changes a slug.</strong> It is in the public URL of every campaign
 * filed under the category and the platform has no redirect table, so a rename would break
 * every shared link with no way to count them. The request bodies simply do not carry one.
 *
 * <h2>Needs {@code CURATE}</h2>
 *
 * <p>The same capability as collections, badges and placement — {@code StaffCapability}
 * folds them together because they are one job: the person who decides that a collection
 * exists is the person who decides what category it draws from.
 *
 * <p><strong>{@code no-store}</strong>, like every response under this prefix. The public
 * taxonomy reads are cached; this one shows unpublished edits and who made them.
 */
@RestController
@RequestMapping("/v1/admin/taxonomy")
public class TaxonomyAdminController {

    private final TaxonomyAdministration taxonomy;

    public TaxonomyAdminController(TaxonomyAdministration taxonomy) {
        this.taxonomy = taxonomy;
    }

    /** The whole tree, with every translation. Unpaged — a tree that pages cannot be reordered. */
    @GetMapping
    public ResponseEntity<TaxonomyAdminResponses.Tree> tree(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Tree.of(taxonomy.tree(callerOf(accessToken))));
    }

    /** The tags creators have used, most-used first. Read-only — see the service. */
    @GetMapping("/tags")
    public ResponseEntity<TaxonomyAdminResponses.TagList> tags(@AuthenticationPrincipal Jwt accessToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.TagList.of(taxonomy.tags(callerOf(accessToken))));
    }

    /** Adds a category. */
    @PostMapping("/categories")
    public ResponseEntity<TaxonomyAdminResponses.Category> createCategory(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody CreateCategoryRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Category.of(taxonomy.createCategory(
                        callerOf(accessToken),
                        request.slug(),
                        request.nameAz(),
                        request.nameEn(),
                        request.sortOrder())));
    }

    /** Renames a category and moves it. The slug is not in the body. */
    @PatchMapping("/categories/{categoryId}")
    public ResponseEntity<TaxonomyAdminResponses.Category> editCategory(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID categoryId,
            @Valid @RequestBody EditRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Category.of(taxonomy.editCategory(
                        callerOf(accessToken),
                        categoryId,
                        request.nameAz(),
                        request.nameEn(),
                        request.sortOrder())));
    }

    /** Adds a subcategory under a category. */
    @PostMapping("/categories/{categoryId}/subcategories")
    public ResponseEntity<TaxonomyAdminResponses.Subcategory> createSubcategory(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID categoryId,
            @Valid @RequestBody CreateCategoryRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Subcategory.of(taxonomy.createSubcategory(
                        callerOf(accessToken),
                        categoryId,
                        request.slug(),
                        request.nameAz(),
                        request.nameEn(),
                        request.sortOrder())));
    }

    /** Renames a subcategory and moves it within its parent. */
    @PatchMapping("/subcategories/{subcategoryId}")
    public ResponseEntity<TaxonomyAdminResponses.Subcategory> editSubcategory(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID subcategoryId,
            @Valid @RequestBody EditRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Subcategory.of(taxonomy.editSubcategory(
                        callerOf(accessToken),
                        subcategoryId,
                        request.nameAz(),
                        request.nameEn(),
                        request.sortOrder())));
    }

    /** Writes a category's name in one locale. An upsert — see the service. */
    @PutMapping("/categories/{categoryId}/translations/{locale}")
    public ResponseEntity<TaxonomyAdminResponses.Translation> translateCategory(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID categoryId,
            @PathVariable @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String locale,
            @Valid @RequestBody TranslationRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Translation.of(taxonomy.translateCategory(
                        callerOf(accessToken), categoryId, locale, request.name())));
    }

    /** The same for a subcategory. */
    @PutMapping("/subcategories/{subcategoryId}/translations/{locale}")
    public ResponseEntity<TaxonomyAdminResponses.Translation> translateSubcategory(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID subcategoryId,
            @PathVariable @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String locale,
            @Valid @RequestBody TranslationRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TaxonomyAdminResponses.Translation.of(taxonomy.translateSubcategory(
                        callerOf(accessToken), subcategoryId, locale, request.name())));
    }

    /**
     * A new category or subcategory.
     *
     * @param slug the permanent handle. Lower case, hyphenated, and never changed after
     *     this call — see the class comment
     * @param nameAz and {@code nameEn} the two names V6 stores on the row itself. §21.1's
     *     other locales are translations, written separately
     */
    public record CreateCategoryRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}[a-z0-9]$") String slug,
            @NotBlank @Size(max = 120) String nameAz,
            @NotBlank @Size(max = 120) String nameEn,
            @Min(0) int sortOrder) {
    }

    /**
     * A rename.
     *
     * <p>Both names are required rather than optional, so that a category cannot end up
     * with a new Azerbaijani name beside its old English one — {@code Category.rename} has
     * the argument. It is a {@code PATCH} because it does not touch the slug, not because
     * its fields are optional.
     */
    public record EditRequest(
            @NotBlank @Size(max = 120) String nameAz,
            @NotBlank @Size(max = 120) String nameEn,
            @Min(0) int sortOrder) {
    }

    /** One locale's name. */
    public record TranslationRequest(@NotBlank @Size(max = 120) String name) {
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
