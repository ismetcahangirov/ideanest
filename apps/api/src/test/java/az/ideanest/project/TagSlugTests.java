package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.project.domain.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fold that decides whether two words are one tag.
 *
 * <p>§11.3 is explicit: the index must fold {@code ə→e, ı→i, ö→o, ü→u, ğ→g,
 * ş→s, ç→c} "because users type both forms interchangeably. A query without
 * diacritics must match text with them, and the reverse." For a free vocabulary
 * that is not a search nicety — it is the difference between one tag with all of
 * its campaigns behind it and two tags with half each, neither of which looks
 * broken from the outside.
 *
 * <p>A plain unit test, and the only place the rule is written.
 */
class TagSlugTests {

    @Test
    @DisplayName("the seven pairs of §11.3 fold, in both cases")
    void azerbaijaniLettersFold() {
        // Exactly the substitutions §11.3 lists, and the uppercase forms with
        // them: "İ".toLowerCase() is an i followed by a combining dot, which is
        // invisible in a diff and fatal to a unique index.
        assertThat(Tag.slugOf("İncəsənət")).isEqualTo("incesenet");
        assertThat(Tag.slugOf("incesenet")).isEqualTo("incesenet");
        assertThat(Tag.slugOf("Ölçü")).isEqualTo("olcu");
        assertThat(Tag.slugOf("işıq")).isEqualTo("isiq");
        assertThat(Tag.slugOf("Gənclər")).isEqualTo("gencler");
        assertThat(Tag.slugOf("ağac")).isEqualTo("agac");
        assertThat(Tag.slugOf("ŞÜŞƏ")).isEqualTo("suse");
    }

    @Test
    @DisplayName("a word and its folded spelling are the same tag")
    void bothSpellingsProduceOneSlug() {
        // The whole reason `slug` and `label` are separate columns. Comparing
        // labels would give these two rows and split the campaigns between them.
        assertThat(Tag.slugOf("Xalçaçılıq")).isEqualTo(Tag.slugOf("xalcaciliq"));
        assertThat(Tag.slugOf("Muğam")).isEqualTo(Tag.slugOf("mugam"));
    }

    @Test
    @DisplayName("spaces and punctuation become one separator, never a leading or trailing one")
    void separatorsAreCollapsed() {
        // The shape `tags_slug_shape` checks: no run of hyphens, no hyphen at
        // either end, so the slug can go into a filter URL unescaped.
        assertThat(Tag.slugOf("open   source")).isEqualTo("open-source");
        assertThat(Tag.slugOf("  Board / Card games! ")).isEqualTo("board-card-games");
        assertThat(Tag.slugOf("3D printing")).isEqualTo("3d-printing");
    }

    @Test
    @DisplayName("a tag keeps the label as it was written")
    void theLabelIsNotFolded() {
        Tag tag = Tag.of("  İncəsənət  ");

        // Displaying the slug would spell a reader's own language wrong. The
        // label is trimmed of surrounding space and otherwise untouched.
        assertThat(tag.getLabel()).isEqualTo("İncəsənət");
        assertThat(tag.getSlug()).isEqualTo("incesenet");
        // Discovery (#42) owns the count; a zero means nobody has counted.
        assertThat(tag.getUsageCount()).isZero();
    }

    @Test
    @DisplayName("a label that folds to nothing usable is refused")
    void anUnusableLabelIsRefused() {
        // A row whose slug could not appear in a filter URL is a facet nobody can
        // navigate to, and one character is not a tag anybody typed on purpose.
        assertThatThrownBy(() -> Tag.of("!!!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tag.of("—")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tag.of("a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tag.of("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tag.of("t".repeat(Tag.LABEL_MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> Tag.of("ok")).doesNotThrowAnyException();
    }
}
