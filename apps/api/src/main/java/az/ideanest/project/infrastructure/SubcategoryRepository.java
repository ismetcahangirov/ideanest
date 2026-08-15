package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.Subcategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The second level of the taxonomy, read.
 *
 * <p>The composite foreign key on {@code projects} already refuses a subcategory
 * whose parent is not the campaign's category. This exists so the refusal reaches
 * the client as a 400 naming the field rather than as a constraint violation and
 * a 500.
 */
public interface SubcategoryRepository extends JpaRepository<Subcategory, UUID> {

    boolean existsByIdAndParentId(UUID id, UUID parentId);

    List<Subcategory> findByParentIdOrderBySortOrderAsc(UUID parentId);

    /**
     * Every subcategory, in display order.
     *
     * <p>For building the whole tree in two queries instead of one per category.
     * The order is by {@code sort_order} alone rather than by parent as well,
     * because the caller groups by parent anyway and the sort within a parent is
     * the only order that reaches a reader.
     */
    List<Subcategory> findAllByOrderBySortOrderAsc();
}
