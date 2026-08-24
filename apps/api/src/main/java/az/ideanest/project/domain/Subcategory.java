package az.ideanest.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
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

    /**
     * A subcategory the taxonomy manager created — issue #309.
     *
     * <p>The slug is permanent for {@code Category}'s reason: it is in the public URL
     * {@code /categories/{category}/{subcategory}}.
     *
     * <p><strong>The parent is permanent too</strong>, and that is the sharper rule.
     * Moving a subcategory to another category silently re-files every campaign under it
     * — a campaign's {@code subcategory_id} does not move, so the change is invisible to
     * the creator and shows up as their campaign appearing somewhere they did not choose.
     * Moving one is retiring it and creating another.
     */
    public Subcategory(UUID id, UUID parentId, String slug, String nameAz, String nameEn, int sortOrder) {
        this.id = Objects.requireNonNull(id, "id");
        this.parentId = Objects.requireNonNull(parentId, "parentId");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.nameAz = Objects.requireNonNull(nameAz, "nameAz");
        this.nameEn = Objects.requireNonNull(nameEn, "nameEn");
        this.sortOrder = sortOrder;
    }

    /** Changes what it is called, in both built-in locales. See {@code Category.rename}. */
    public void rename(String nameAz, String nameEn) {
        this.nameAz = Objects.requireNonNull(nameAz, "nameAz");
        this.nameEn = Objects.requireNonNull(nameEn, "nameEn");
    }

    /** Moves it within its parent. */
    public void reorder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

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
