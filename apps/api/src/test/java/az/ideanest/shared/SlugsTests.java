package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlugsTests {

    @Test
    @DisplayName("Azerbaijani letters fold to their Latin equivalents")
    void foldsAzerbaijani() {
        // ə has no Unicode decomposition, so normalisation alone leaves it in
        // place and the slug ends up with a character every URL handler between
        // here and the browser will treat differently.
        assertThat(Slugs.slugify("İsmət Cahangirov")).isEqualTo("ismet-cahangirov");
        assertThat(Slugs.slugify("Şəbnəm Ələkbərova")).isEqualTo("sebnem-elekberova");
        assertThat(Slugs.slugify("Gülnar Ömərova")).isEqualTo("gulnar-omerova");
        assertThat(Slugs.slugify("Çingiz Işıqlı")).isEqualTo("cingiz-isiqli");
    }

    @Test
    @DisplayName("accented Latin decomposes and loses its marks")
    void foldsAccents() {
        assertThat(Slugs.slugify("Zoë Müller")).isEqualTo("zoe-muller");
        assertThat(Slugs.slugify("Renée Ångström")).isEqualTo("renee-angstrom");
    }

    @Test
    @DisplayName("punctuation and spacing collapse to single hyphens")
    void collapsesSeparators() {
        assertThat(Slugs.slugify("  Ismet   --  Cahangirov!  ")).isEqualTo("ismet-cahangirov");
        assertThat(Slugs.slugify("O'Brien & Sons")).isEqualTo("o-brien-sons");
    }

    @Test
    @DisplayName("a name in an untransliterated script yields nothing, rather than nonsense")
    void returnsEmptyWhenNothingSurvives() {
        // The caller substitutes a fallback. Inventing one here would hide the
        // case from the only code in a position to decide what to do about it.
        assertThat(Slugs.slugify("北京")).isEmpty();
        assertThat(Slugs.slugify("   ")).isEmpty();
    }
}
