package az.ideanest.project.application;

import az.ideanest.project.domain.Category;
import az.ideanest.project.domain.Subcategory;
import az.ideanest.project.infrastructure.CategoryRepository;
import az.ideanest.project.infrastructure.SubcategoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category tree, read whole.
 *
 * <p>The editor cannot ask a creator to choose a category without being able to
 * list them, and {@code PATCH /v1/projects/{id}} validates {@code categoryId}
 * against these rows — so the endpoint that publishes them belongs with the
 * migration that seeds them rather than with a later epic. §10.2 files
 * {@code GET /v1/categories} under discovery, which is where the faceted and
 * localised version of this will live; a read of two seeded tables is not worth
 * holding the campaign editor hostage to.
 *
 * <p><strong>Both names are returned, not one.</strong> Choosing between
 * {@code name_az} and {@code name_en} means honouring {@code Accept-Language},
 * and internationalised routing (#123) owns that decision for the whole API.
 * Picking one here would be a localisation policy invented in a leaf.
 */
@Service
public class Taxonomy {

    /** @param subcategories in display order, possibly empty */
    public record TaxonomyCategory(
            UUID id, String slug, String nameAz, String nameEn, List<TaxonomySubcategory> subcategories) {
    }

    public record TaxonomySubcategory(UUID id, String slug, String nameAz, String nameEn) {
    }

    private final CategoryRepository categories;
    private final SubcategoryRepository subcategories;

    public Taxonomy(CategoryRepository categories, SubcategoryRepository subcategories) {
        this.categories = categories;
        this.subcategories = subcategories;
    }

    /**
     * Every category with its children, in the platform's display order.
     *
     * <p>Two queries rather than one per category. The tree is small and static,
     * but an N+1 over a list that every editor session loads is the kind of query
     * pattern that is only ever noticed once there are enough sessions for it to
     * matter.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyCategory> all() {
        Map<UUID, List<TaxonomySubcategory>> children = new LinkedHashMap<>();
        for (Subcategory subcategory : subcategories.findAllByOrderBySortOrderAsc()) {
            children.computeIfAbsent(subcategory.getParentId(), key -> new ArrayList<>())
                    .add(new TaxonomySubcategory(
                            subcategory.getId(),
                            subcategory.getSlug(),
                            subcategory.getNameAz(),
                            subcategory.getNameEn()));
        }

        List<TaxonomyCategory> tree = new ArrayList<>();
        for (Category category : categories.findAllByOrderBySortOrderAsc()) {
            tree.add(new TaxonomyCategory(
                    category.getId(),
                    category.getSlug(),
                    category.getNameAz(),
                    category.getNameEn(),
                    List.copyOf(children.getOrDefault(category.getId(), List.of()))));
        }
        return List.copyOf(tree);
    }
}
