package az.ideanest.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The second level of the taxonomy.
 *
 * <p>Read-only, like {@link Category}. The parent is an identifier rather than an
 * association: the only question asked of this table is "does this subcategory
 * belong to that category", and an association would load the parent row to
 * answer a question about its primary key.
 */
@Entity
@Table(name = "subcategories")
public class Subcategory {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "parent_id", nullable = false)
    private UUID parentId;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "name_az", nullable = false)
    private String nameAz;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Subcategory() {
        // JPA. Seeded by the migration; nothing in the application creates one.
    }

    public UUID getId() {
        return id;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getSlug() {
        return slug;
    }

    public String getNameAz() {
        return nameAz;
    }

    public String getNameEn() {
        return nameEn;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
