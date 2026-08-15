package az.ideanest.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A top-level category in the project taxonomy.
 *
 * <p>Read-only here. The rows are seeded by the migration and the taxonomy is
 * curated by the discovery epic (#42), which is also where the browsing surfaces
 * that need both names live. This module needs one thing from it: whether the
 * category a creator selected exists.
 *
 * <p>Both names are columns rather than rows in a translation table because §11.3
 * treats Azerbaijani and English as the two languages the platform is written in,
 * not as translations of a default. A category with a missing name is a
 * navigation item that renders as a key.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "name_az", nullable = false)
    private String nameAz;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Category() {
        // JPA. Nothing in the application creates a category; the migration does.
    }

    public UUID getId() {
        return id;
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
