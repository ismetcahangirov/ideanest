package az.ideanest.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * What a category is called, in one language.
 *
 * <p>Rows rather than columns, which is the change V11 makes. {@code name_az}
 * and {@code name_en} could express the two languages the platform was written
 * in; §21.1 names four — {@code az}, {@code en}, {@code ru}, {@code tr} — and
 * each further one would have been a column, a migration, and a deployment.
 * §4.3 asks for the opposite: "the taxonomy is data, not code: it must be
 * editable without a deployment".
 *
 * <p><strong>Read-only here</strong>, like {@link Category}. The rows are
 * seeded by the migration and curated by the discovery epic (#42); Russian
 * arrives as an INSERT rather than as a release, which is the whole point of
 * the table.
 *
 * <p>The category is an identifier rather than an association, for the reason
 * {@link Subcategory} gives about its parent: every read of this table groups by
 * that identifier, and an association would load the category row to answer a
 * question about its primary key.
 *
 * <p><strong>There is no guarantee in the schema that an {@code az} row
 * exists.</strong> "At least one row of a given locale" is a statement about a
 * set of sibling rows, which no per-row check can see and no index can require;
 * V11 explains the alternatives it rejected. The invariant is held by the seed,
 * by {@code TaxonomySchemaTests}, and — for reading — by the explicit fallback
 * chain in {@code Taxonomy}, which never assumes the row is there.
 */
@Entity
@Table(name = "category_translations")
public class CategoryTranslation {

    /**
     * The category and the language.
     *
     * <p>No surrogate key: the identity of a translation is the thing it
     * translates and the language it translates it into. A generated identifier
     * would give the table a second way to say the same thing, and with it a way
     * to hold two names for one pair.
     */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "category_id", nullable = false, updatable = false)
        private UUID categoryId;

        @Column(name = "locale", nullable = false, updatable = false)
        private String locale;

        protected Key() {
            // JPA constructs it reflectively when it reads a row.
        }

        public Key(UUID categoryId, String locale) {
            this.categoryId = Objects.requireNonNull(categoryId, "A translation names its category");
            this.locale = Objects.requireNonNull(locale, "A translation names its locale");
        }

        public UUID getCategoryId() {
            return categoryId;
        }

        public String getLocale() {
            return locale;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(categoryId, key.categoryId)
                    && Objects.equals(locale, key.locale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(categoryId, locale);
        }

        @Override
        public String toString() {
            return "Key[category=" + categoryId + ", locale=" + locale + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "name", nullable = false)
    private String name;

    protected CategoryTranslation() {
        // JPA. Nothing in the application writes a translation; the migration does.
    }

    public UUID getCategoryId() {
        return id.getCategoryId();
    }

    /** One of the codes of §21.1, which the database also checks. */
    public String getLocale() {
        return id.getLocale();
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CategoryTranslation translation && Objects.equals(id, translation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "CategoryTranslation[" + id + "]";
    }
}
