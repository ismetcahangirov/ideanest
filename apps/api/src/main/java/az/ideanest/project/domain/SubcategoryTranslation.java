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
 * What a subcategory is called, in one language.
 *
 * <p>The second level of what {@link CategoryTranslation} does for the first,
 * and every decision on that class applies here unchanged: rows rather than
 * columns so that a language is data, no surrogate key because the pair is the
 * identity, read-only because the migration and the discovery epic are what
 * write it, and no schema guarantee that the {@code az} row exists — the
 * fallback chain in {@code Taxonomy} is what makes reading safe.
 */
@Entity
@Table(name = "subcategory_translations")
public class SubcategoryTranslation {

    /** The subcategory and the language. See {@code CategoryTranslation.Key}. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "subcategory_id", nullable = false, updatable = false)
        private UUID subcategoryId;

        @Column(name = "locale", nullable = false, updatable = false)
        private String locale;

        protected Key() {
            // JPA.
        }

        public Key(UUID subcategoryId, String locale) {
            this.subcategoryId = Objects.requireNonNull(subcategoryId, "A translation names its subcategory");
            this.locale = Objects.requireNonNull(locale, "A translation names its locale");
        }

        public UUID getSubcategoryId() {
            return subcategoryId;
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
                    && Objects.equals(subcategoryId, key.subcategoryId)
                    && Objects.equals(locale, key.locale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(subcategoryId, locale);
        }

        @Override
        public String toString() {
            return "Key[subcategory=" + subcategoryId + ", locale=" + locale + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * A translation the taxonomy manager wrote — issue #309.
     *
     * <p>Until #309 nothing in the application wrote one; V11 seeded them and §4.3's
     * requirement that the taxonomy be editable without a deployment was unmet.
     *
     * <p><strong>A locale with no row falls back to {@code name_en}</strong> rather than
     * rendering blank, which is why creating a translation is an upsert and deleting one
     * is a safe operation: the subcategory keeps a name in every locale either way.
     */
    public SubcategoryTranslation(Key id, String name) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.name = java.util.Objects.requireNonNull(name, "name");
    }

    /** Rewrites the translated name. The key never changes; a new locale is a new row. */
    public void rename(String name) {
        this.name = java.util.Objects.requireNonNull(name, "name");
    }

    protected SubcategoryTranslation() {
        // JPA.
    }

    public UUID getSubcategoryId() {
        return id.getSubcategoryId();
    }

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
        return other instanceof SubcategoryTranslation translation && Objects.equals(id, translation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SubcategoryTranslation[" + id + "]";
    }
}
