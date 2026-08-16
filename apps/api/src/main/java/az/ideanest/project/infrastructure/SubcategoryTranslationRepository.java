package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.SubcategoryTranslation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every subcategory name in every language, read.
 *
 * <p>The same shape and the same reasoning as {@link CategoryTranslationRepository}:
 * the tree is built in one pass and each name is resolved through a fallback
 * chain, so filtering by locale in the query would remove the rows the fallback
 * exists to find.
 */
public interface SubcategoryTranslationRepository
        extends JpaRepository<SubcategoryTranslation, SubcategoryTranslation.Key> {

    List<SubcategoryTranslation> findAllByOrderByIdSubcategoryIdAscIdLocaleAsc();
}
