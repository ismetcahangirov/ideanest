package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.project.application.Taxonomy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which language a taxon is named in, and what happens when it is not named in
 * that one.
 *
 * <p>A plain unit test: negotiation and the fallback chain are pure functions,
 * and starting a PostgreSQL container to check that a missing Russian name
 * degrades to Azerbaijani would make the suite slower for no coverage.
 *
 * <p>This is the part of localisation that fails invisibly. A client asking for
 * a language nobody has translated does not get an error; it gets whatever the
 * fallback decides, and the two wrong answers — an empty string and the wrong
 * language — both render as a plausible page.
 */
class TaxonomyLocaleTests {

    private static final Map<String, String> ART = names("az", "İncəsənət", "en", "Art");

    private static Map<String, String> names(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return Map.copyOf(map);
    }

    @Nested
    @DisplayName("resolving a name")
    class Resolving {

        @Test
        @DisplayName("the requested language is used when the taxon has it")
        void theRequestedLanguageWins() {
            assertThat(Taxonomy.resolveName(ART, "en", "art")).isEqualTo("Art");
            assertThat(Taxonomy.resolveName(ART, "az", "art")).isEqualTo("İncəsənət");
        }

        @Test
        @DisplayName("a language the taxon has no row for falls back to Azerbaijani")
        void anUntranslatedLanguageFallsBackToThePrimary() {
            // §21.1 makes Azerbaijani the primary language, so it is the fallback
            // rather than English. Russian is phase 1 and deliberately unseeded:
            // the whole point of the translation table is that it arrives as data,
            // and until it does a Russian-speaking visitor sees the platform's own
            // language rather than a blank navigation.
            assertThat(Taxonomy.resolveName(ART, "ru", "art")).isEqualTo("İncəsənət");
            assertThat(Taxonomy.resolveName(ART, "tr", "art")).isEqualTo("İncəsənət");
        }

        @Test
        @DisplayName("a language nobody ships falls back to Azerbaijani too")
        void anUnknownCodeFallsBackToThePrimary() {
            // resolveName is reached with a negotiated code, so this should not
            // happen -- which is exactly why it is checked. A caller that grows a
            // second way in must not be able to produce a null name.
            assertThat(Taxonomy.resolveName(ART, "de", "art")).isEqualTo("İncəsənət");
            assertThat(Taxonomy.resolveName(ART, "", "art")).isEqualTo("İncəsənət");
        }

        @Test
        @DisplayName("a taxon with no Azerbaijani name degrades to its slug, never to nothing")
        void theLastResortIsTheSlug() {
            // No constraint can require that an `az` row exists -- "at least one
            // row of a given locale" is a statement about sibling rows -- so a
            // taxon added by hand without one is possible. The difference between
            // this step and an empty string is whether a creator can tell what
            // they are choosing in the editor's category select.
            assertThat(Taxonomy.resolveName(names("en", "Art"), "ru", "art")).isEqualTo("art");
            assertThat(Taxonomy.resolveName(Map.of(), "az", "art")).isEqualTo("art");
        }
    }

    @Nested
    @DisplayName("negotiating a language")
    class Negotiating {

        @Test
        @DisplayName("no header at all is Azerbaijani")
        void theDefaultIsThePrimaryLanguage() {
            assertThat(Taxonomy.localeFor(null)).isEqualTo("az");
            assertThat(Taxonomy.localeFor("   ")).isEqualTo("az");
        }

        @Test
        @DisplayName("quality values are the user's stated order of preference")
        void qualityValuesAreHonoured() {
            // What a browser actually sends. Reading the first two characters
            // would work here by accident and stop working the moment somebody's
            // browser lists a language it cannot get first.
            assertThat(Taxonomy.localeFor("en-GB,en;q=0.9,az;q=0.8")).isEqualTo("en");
            assertThat(Taxonomy.localeFor("de;q=0.9,ru;q=0.8")).isEqualTo("ru");
        }

        @Test
        @DisplayName("a regional variant resolves to the language the platform ships")
        void aRegionalVariantIsTruncated() {
            // RFC 4647 lookup truncates the range, so somebody asking for British
            // English gets English rather than Azerbaijani.
            assertThat(Taxonomy.localeFor("en-US")).isEqualTo("en");
            assertThat(Taxonomy.localeFor("az-Latn-AZ")).isEqualTo("az");
        }

        @Test
        @DisplayName("a language the platform does not ship is Azerbaijani")
        void anUnsupportedLanguageIsThePrimaryOne() {
            assertThat(Taxonomy.localeFor("de")).isEqualTo("az");
            assertThat(Taxonomy.localeFor("fr-FR,fr;q=0.9")).isEqualTo("az");
        }

        @Test
        @DisplayName("a malformed header is answered rather than rejected")
        void aMalformedHeaderDoesNotFailTheRequest() {
            // GET /v1/categories is public, cacheable, and unauthenticated. A
            // client sending nonsense in this header is a client bug, and turning
            // it into a 400 would take the category list away from a creator for a
            // reason they cannot act on.
            assertThat(Taxonomy.localeFor("=====")).isEqualTo("az");
            assertThat(Taxonomy.localeFor(";q=")).isEqualTo("az");
        }
    }
}
