package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.CategoryTranslation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every category name in every language, read.
 *
 * <p>No finder by locale, and that is deliberate. {@code Taxonomy} builds the
 * whole tree in one pass and resolves each name through a fallback chain —
 * requested locale, then {@code az}, then the slug — so it needs every
 * translation of a category rather than the rows for one language: a query
 * filtered to {@code ru} would return nothing for a taxon nobody has translated
 * yet and the fallback would have nothing to fall back to.
 *
 * <p>Reading the table whole is affordable because it is: fifteen categories
 * times at most four locales is sixty short rows, static between deployments,
 * and behind an ETag.
 */
public interface CategoryTranslationRepository extends JpaRepository<CategoryTranslation, CategoryTranslation.Key> {

    /** Ordered so that the tree, and with it the ETag over it, is stable between reads. */
    List<CategoryTranslation> findAllByOrderByIdCategoryIdAscIdLocaleAsc();
}
