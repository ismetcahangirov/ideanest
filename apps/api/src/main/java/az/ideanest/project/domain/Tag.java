package az.ideanest.project.domain;

import az.ideanest.shared.Identifiers;
import az.ideanest.shared.Slugs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * One word of the free vocabulary of §4.3.
 *
 * <p>A tag is not a category. A category is <em>where</em> a campaign is filed —
 * one place, from a list the platform curates, and a submission requirement
 * under §5.3. A tag is what a campaign is <em>about</em>, in the creator's own
 * words, and there are several. That is why they are separate tables and why
 * this one has no tree in it.
 *
 * <p><strong>Two names, and they are not the same name.</strong>
 * {@link #getLabel()} is the word as the first creator to use it wrote it, and
 * is what every surface displays. {@link #getSlug()} is the folded form, and is
 * the only thing ever compared. §11.3 requires the index to fold
 * {@code ə→e, ı→i, ö→o, ü→u, ğ→g, ş→s, ç→c} "because users type both forms
 * interchangeably" — so {@code İncəsənət} and {@code incesenet} have to be one
 * tag rather than two sets of campaigns nobody can see at the same time. The
 * fold is lossy in both directions, which is why both are kept: showing the slug
 * would spell a reader's own language wrong, and comparing labels would split
 * the tag.
 *
 * <p><strong>The fold is not defined here.</strong> It used to be, on the
 * grounds that {@code unaccent()} does not fold {@code ə} at all — which is
 * true, and is why V13 does not use {@code unaccent} either. But #43 needs the
 * same fold in two more places, so it is now {@link Slugs#fold} and the
 * database's {@code ideanest_fold}, and those two are pinned to each other by a
 * test. A tag slug and a search term that disagreed about what {@code Seçənək}
 * folds to would be two answers to one question.
 *
 * <p><strong>Nothing writes tags yet.</strong> Attaching one to a campaign is
 * the campaign editor's field, §10.2 lists no {@code /v1/tags} route, and the
 * cap on tags per campaign belongs to the service that will attach them (V11
 * says why it cannot be a constraint). {@link #of} exists so that service does
 * not have to reinvent the fold when it arrives.
 */
@Entity
@Table(name = "tags")
public class Tag {

    /** As {@code tags_slug_length} also requires. */
    public static final int SLUG_MIN_LENGTH = 2;

    public static final int SLUG_MAX_LENGTH = 40;

    /** As {@code tags_label_length} also requires. */
    public static final int LABEL_MAX_LENGTH = 40;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "label", nullable = false)
    private String label;

    /**
     * Denormalised, and not maintained by anything in this module.
     *
     * <p>Discovery (#42) owns trending tags (D-09) and the live faceted counts
     * (D-10), and it is that epic which decides whether this is updated as
     * pledges land, recomputed on a schedule, or replaced by a count over
     * {@code project_tags}. Until then every row sits at zero, and a zero here
     * means "nobody has counted" rather than "no campaigns".
     */
    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    protected Tag() {
        // JPA.
    }

    /**
     * A tag from the word a creator typed.
     *
     * @param label kept as written, trimmed of surrounding space only. The
     *     capitals and the diacritics are the point of storing it separately
     * @throws IllegalArgumentException when the label is blank, longer than
     *     {@link #LABEL_MAX_LENGTH}, or folds to something that is not a usable
     *     slug — "!!!" and "—" are not tags, and a row whose slug could not
     *     appear in a filter URL is a facet nobody can navigate to
     */
    public static Tag of(String label) {
        Objects.requireNonNull(label, "A tag has a label");
        String trimmed = label.trim();
        if (trimmed.isEmpty() || trimmed.length() > LABEL_MAX_LENGTH) {
            throw new IllegalArgumentException("A tag label is 1 to " + LABEL_MAX_LENGTH + " characters");
        }

        String slug = slugOf(trimmed);
        if (slug.length() < SLUG_MIN_LENGTH || slug.length() > SLUG_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "'" + trimmed + "' does not fold to a tag of " + SLUG_MIN_LENGTH + " to " + SLUG_MAX_LENGTH
                            + " characters");
        }

        Tag tag = new Tag();
        tag.id = Identifiers.newIdentifier();
        tag.slug = slug;
        tag.label = trimmed;
        tag.usageCount = 0;
        return tag;
    }

    /**
     * The comparable form of a word: {@link Slugs#fold}, then everything that is
     * not a letter or a digit collapsed to a single hyphen.
     *
     * <p>Not {@link Slugs#slugify}, which is the other slug in the platform and
     * additionally decomposes and strips combining marks. A tag label is a word
     * a creator typed rather than a display name the platform assigns, and
     * {@code tags_slug_shape} is the constraint this has to satisfy.
     */
    public static String slugOf(String text) {
        String lowered = Slugs.fold(text);

        StringBuilder slug = new StringBuilder(lowered.length());
        for (int index = 0; index < lowered.length(); index++) {
            char character = lowered.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                slug.append(character);
            } else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
                // One separator however many spaces or punctuation marks were
                // between the words, and never a leading one — the shape
                // `tags_slug_shape` checks has no run of hyphens in it.
                slug.append('-');
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') {
            slug.deleteCharAt(slug.length() - 1);
        }
        return slug.toString();
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getLabel() {
        return label;
    }

    public int getUsageCount() {
        return usageCount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Tag tag && Objects.equals(id, tag.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Tag[" + slug + "]";
    }
}
