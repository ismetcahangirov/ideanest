package az.ideanest.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
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
        // JPA.
    }

    /**
     * A category the taxonomy manager created — issue #309.
     *
     * <p>Until #309 this comment said "nothing in the application creates a category; the
     * migration does", and that was the whole of what AD-08 was blocked on: §4.3 requires
     * the taxonomy be editable without a deployment, and every category on the platform
     * came out of V6's seed.
     *
     * <p><strong>The slug is set once and never changed.</strong> It is in the public URL
     * of every campaign filed under the category — {@code /categories/{category}} — and
     * renaming it would break every link anybody has ever shared, with no redirect to
     * catch them. The display names are what the manager edits; a category whose slug is
     * genuinely wrong is retired and replaced.
     */
    public Category(UUID id, String slug, String nameAz, String nameEn, int sortOrder) {
        this.id = Objects.requireNonNull(id, "id");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.nameAz = Objects.requireNonNull(nameAz, "nameAz");
        this.nameEn = Objects.requireNonNull(nameEn, "nameEn");
        this.sortOrder = sortOrder;
    }

    /**
     * Changes what the category is called, in both of §21.1's built-in locales.
     *
     * <p>Both at once rather than one at a time, so that a category cannot sit in a state
     * where the Azerbaijani name is the new one and the English is the old — which is what
     * a per-field patch produces the first time somebody is interrupted.
     */
    public void rename(String nameAz, String nameEn) {
        this.nameAz = Objects.requireNonNull(nameAz, "nameAz");
        this.nameEn = Objects.requireNonNull(nameEn, "nameEn");
    }

    /** Moves it in the navigation. */
    public void reorder(int sortOrder) {
        this.sortOrder = sortOrder;
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
