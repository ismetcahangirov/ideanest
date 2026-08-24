package az.ideanest.project.api;

import az.ideanest.project.application.TaxonomyAdministration;
import az.ideanest.project.domain.CategoryTranslation;
import az.ideanest.project.domain.SubcategoryTranslation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AD-08's screen, as the service describes it — issue #309.
 */
public final class TaxonomyAdminResponses {

    private TaxonomyAdminResponses() {
    }

    /** One category, without its children. */
    public record Category(
            UUID id, String slug, String nameAz, String nameEn, int sortOrder, Map<String, String> translations) {

        public static Category of(az.ideanest.project.domain.Category category) {
            return of(category, Map.of());
        }

        static Category of(az.ideanest.project.domain.Category category, Map<String, String> translations) {
            return new Category(
                    category.getId(),
                    category.getSlug(),
                    category.getNameAz(),
                    category.getNameEn(),
                    category.getSortOrder(),
                    translations);
        }
    }

    /** One subcategory. */
    public record Subcategory(
            UUID id,
            UUID parentId,
            String slug,
            String nameAz,
            String nameEn,
            int sortOrder,
            Map<String, String> translations) {

        public static Subcategory of(az.ideanest.project.domain.Subcategory subcategory) {
            return of(subcategory, Map.of());
        }

        static Subcategory of(az.ideanest.project.domain.Subcategory subcategory, Map<String, String> translations) {
            return new Subcategory(
                    subcategory.getId(),
                    subcategory.getParentId(),
                    subcategory.getSlug(),
                    subcategory.getNameAz(),
                    subcategory.getNameEn(),
                    subcategory.getSortOrder(),
                    translations);
        }
    }

    /** A category with the subcategories filed under it. */
    public record Branch(Category category, List<Subcategory> subcategories) {
    }

    /**
     * The whole taxonomy, nested.
     *
     * <p><strong>The nesting happens here and not in the service.</strong> The service
     * returns four flat lists because that is what the four tables are; the shape the
     * screen wants — a category, its children, and every locale of both — is not the shape
     * any of them has, and building it in the domain would mean a second traversal of the
     * translations to serialise it.
     *
     * <p>Sorted by {@code sortOrder} and then by slug. The tie-break matters: two
     * categories may share a position while somebody is reordering them, and a tree whose
     * rows swap between reloads is one where somebody edits the wrong line.
     */
    public record Tree(List<Branch> branches) {

        public static Tree of(TaxonomyAdministration.TaxonomyTree tree) {
            Map<UUID, Map<String, String>> categoryTranslations = tree.categoryTranslations().stream()
                    .collect(Collectors.groupingBy(
                            CategoryTranslation::getCategoryId,
                            Collectors.toMap(CategoryTranslation::getLocale, CategoryTranslation::getName)));

            Map<UUID, Map<String, String>> subcategoryTranslations = tree.subcategoryTranslations().stream()
                    .collect(Collectors.groupingBy(
                            SubcategoryTranslation::getSubcategoryId,
                            Collectors.toMap(
                                    SubcategoryTranslation::getLocale, SubcategoryTranslation::getName)));

            Map<UUID, List<Subcategory>> children = tree.subcategories().stream()
                    .sorted(Comparator.comparingInt(az.ideanest.project.domain.Subcategory::getSortOrder)
                            .thenComparing(az.ideanest.project.domain.Subcategory::getSlug))
                    .map(subcategory -> Subcategory.of(
                            subcategory,
                            subcategoryTranslations.getOrDefault(subcategory.getId(), Map.of())))
                    .collect(Collectors.groupingBy(Subcategory::parentId));

            List<Branch> branches = tree.categories().stream()
                    .sorted(Comparator.comparingInt(az.ideanest.project.domain.Category::getSortOrder)
                            .thenComparing(az.ideanest.project.domain.Category::getSlug))
                    .map(category -> new Branch(
                            Category.of(
                                    category, categoryTranslations.getOrDefault(category.getId(), Map.of())),
                            children.getOrDefault(category.getId(), List.of())))
                    .toList();

            return new Tree(branches);
        }
    }

    /** One locale's name for one entry. */
    public record Translation(UUID entryId, String locale, String name) {

        public static Translation of(CategoryTranslation translation) {
            return new Translation(
                    translation.getCategoryId(), translation.getLocale(), translation.getName());
        }

        public static Translation of(SubcategoryTranslation translation) {
            return new Translation(
                    translation.getSubcategoryId(), translation.getLocale(), translation.getName());
        }
    }

    /**
     * One tag and how heavily it is used.
     *
     * <p>{@code usageCount} is the whole reason this list is on the screen: it is the input
     * to "should this be a category", which is the only editorial decision §4.3 gives
     * anybody about tags.
     */
    public record Tag(UUID id, String slug, String label, int usageCount) {

        public static Tag of(az.ideanest.project.domain.Tag tag) {
            return new Tag(tag.getId(), tag.getSlug(), tag.getLabel(), tag.getUsageCount());
        }
    }

    /** The tags, most-used first. */
    public record TagList(List<Tag> tags) {

        public static TagList of(List<az.ideanest.project.domain.Tag> tags) {
            return new TagList(tags.stream()
                    .sorted(Comparator.comparingInt(az.ideanest.project.domain.Tag::getUsageCount)
                            .reversed()
                            .thenComparing(az.ideanest.project.domain.Tag::getSlug))
                    .map(Tag::of)
                    .toList());
        }
    }
}
