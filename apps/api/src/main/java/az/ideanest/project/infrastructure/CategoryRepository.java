package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The taxonomy, read.
 *
 * <p>Nothing writes to it: the rows are seeded by V6 and curated by the discovery
 * epic. What this module needs is the existence check that keeps a campaign from
 * being filed under a category that was removed between the editor loading the
 * list and the creator choosing from it.
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** By slug, which is the handle everything outside the database uses. */
    Optional<Category> findBySlug(String slug);

    List<Category> findAllByOrderBySortOrderAsc();
}
