package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.Slugs;
import az.ideanest.support.Campaigns;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §11.3, character by character: <strong>a query without diacritics matches text
 * with them, and the reverse.</strong>
 *
 * <p>That sentence is the whole of the issue, and it is symmetric on purpose.
 * Azerbaijani is typed on whatever keyboard is to hand: the same person writes
 * "seçənək" on a phone and "secenek" on a borrowed laptop, and a search that
 * honoured only one direction would be a search that works for half of its users
 * and looks broken to the other half — without either of them being able to say
 * why.
 *
 * <p>So every one of the seven pairs is seeded twice — once written with the
 * letter and once without — and searched twice. Real words rather than single
 * letters: a fold that is right for "ə" in isolation and wrong inside a word,
 * because of where the lower-casing happens, would pass a test made of letters.
 *
 * <p>The last test is the one that keeps the two implementations honest. The fold
 * necessarily exists twice — once in the database, because the index is built from
 * it, and once in Java, because the taxonomy is matched in memory for suggestions
 * — and two implementations of one rule drift. This pins them to each other over a
 * shared table of cases, so the drift is a failing test rather than a search that
 * silently stops finding things.
 */
class SearchFoldingTests extends DiscoveryTestSupport {

    /**
     * §11.3's seven pairs, as words somebody would actually type.
     *
     * <p>Each row is {@code {slug, the word with its diacritics, the same word
     * without}}. The two spellings are seeded as two campaigns and each query has to
     * find both.
     */
    private static final String[][] PAIRS = {
        {"schwa", "Hekayə", "hekaye"},
        {"dotless-i", "Işıqlı", "isiqli"},
        {"o-umlaut", "Göllər", "goller"},
        {"u-umlaut", "Üzümlük", "uzumluk"},
        {"g-breve", "Dağlar", "daglar"},
        {"s-cedilla", "Quşlar", "quslar"},
        {"c-cedilla", "Çaylar", "caylar"},
    };

    @BeforeEach
    void seedBothSpellingsOfEveryPair() {
        Campaigns.clear(dataSource);
        UUID creator = Campaigns.creator(dataSource, "folding-creator", "Səbinə Əliyeva");

        for (String[] pair : PAIRS) {
            Campaigns.seed(dataSource, creator, pair[0] + "-written")
                    .state("LIVE")
                    .title(pair[1])
                    .category("games")
                    .insert();
            Campaigns.seed(dataSource, creator, pair[0] + "-plain")
                    .state("LIVE")
                    .title(pair[2])
                    .category("games")
                    .insert();
        }
    }

    @Test
    @DisplayName("a query without diacritics finds text with them, for every pair in §11.3")
    void plainQueriesFindDiacriticText() {
        for (String[] pair : PAIRS) {
            assertThat(slugs(search("?q={q}&limit=100", pair[2])))
                    .withFailMessage("searching '%s' did not find the campaign titled '%s'", pair[2], pair[1])
                    .contains(pair[0] + "-written");
        }
    }

    @Test
    @DisplayName("a query with diacritics finds text without them, for every pair in §11.3")
    void diacriticQueriesFindPlainText() {
        for (String[] pair : PAIRS) {
            assertThat(slugs(search("?q={q}&limit=100", pair[1])))
                    .withFailMessage("searching '%s' did not find the campaign titled '%s'", pair[1], pair[2])
                    .contains(pair[0] + "-plain");
        }
    }

    @Test
    @DisplayName("either spelling returns exactly the same campaigns")
    void theTwoSpellingsAreOneQuery() {
        // Stronger than the two above together: not merely that each finds the other's
        // campaign, but that the two queries are indistinguishable. A fold that mapped
        // one spelling to something the other does not reach would pass a containment
        // check and fail this.
        for (String[] pair : PAIRS) {
            assertThat(slugs(search("?q={q}&limit=100", pair[1])))
                    .withFailMessage("'%s' and '%s' are not the same query", pair[1], pair[2])
                    .containsExactlyInAnyOrderElementsOf(slugs(search("?q={q}&limit=100", pair[2])))
                    .contains(pair[0] + "-written", pair[0] + "-plain");
        }
    }

    @Test
    @DisplayName("the fold is case-insensitive in both spellings")
    void capitalsFoldTheSameWay() {
        // "İ".toLowerCase() is an i with a combining dot above, and PostgreSQL's
        // lower() disagrees with Java's about it depending on the database ctype. Both
        // sides map İ straight to i before any lower-casing, which is why this passes.
        assertThat(slugs(search("?q={q}&limit=100", "IŞIQLI"))).contains("dotless-i-written", "dotless-i-plain");
        assertThat(slugs(search("?q={q}&limit=100", "HEKAYƏ"))).contains("schwa-written", "schwa-plain");
        assertThat(slugs(search("?q={q}&limit=100", "ÜZÜMLÜK"))).contains("u-umlaut-written", "u-umlaut-plain");
    }

    @Test
    @DisplayName("the creator's name folds too, so a search for it finds their campaigns")
    void theCreatorNameIsFolded() {
        // D-01 puts the creator in the index, and a creator's name is exactly the kind
        // of text that carries diacritics: "Səbinə" is not findable as "Sebine" unless
        // the name goes through the same fold as everything else.
        assertThat(slugs(search("?q={q}&limit=100", "Sebine"))).hasSize(PAIRS.length * 2);
        assertThat(slugs(search("?q={q}&limit=100", "Səbinə"))).hasSize(PAIRS.length * 2);
        assertThat(slugs(search("?q={q}&limit=100", "eliyeva"))).hasSize(PAIRS.length * 2);
    }

    @Test
    @DisplayName("the database's fold and Java's fold are the same function")
    void oneFoldInTwoLanguages() {
        // WHY THERE ARE TWO. The index is built from ideanest_fold, so a query folded
        // only in Java would not match it — which is why every text predicate in
        // PostgresSearchService folds in SQL. But the category tree is matched in
        // memory for suggestions (it is a hundred rows that are already loaded, and
        // Taxonomy owns the locale fallback chain), and that comparison has to fold in
        // Java. Two implementations of one rule drift; this is what catches it.
        List<String> cases = List.of(
                "Seçənək",
                "secenek",
                "İncəsənət",
                "incesenet",
                "ƏĞIİÖÜŞÇ",
                "əğıiöüşç",
                "Işıq",
                "ışıq",
                "IŞIQ",
                "Gözəl bağça",
                "Robot dostum",
                "ROBOT",
                "Fotoqrafiya albomu",
                // Not Azerbaijani, and it still has to agree: §21.1 ships four
                // languages and one column holds all of them.
                "Привет Мир",
                "Café",
                // Nothing to fold, and nothing to change.
                "already-folded-2024",
                "!!! ??? ...",
                "");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String value : cases) {
            String inTheDatabase = jdbc.queryForObject("SELECT ideanest_fold(?)", String.class, value);
            assertThat(Slugs.fold(value))
                    .withFailMessage(
                            "Slugs.fold(%s) is '%s' but ideanest_fold(%s) is '%s'",
                            value, Slugs.fold(value), value, inTheDatabase)
                    .isEqualTo(inTheDatabase);
        }
    }

    @Test
    @DisplayName("the tag slug and the search index agree about what a word folds to")
    void tagSlugsUseTheSameFold() {
        // Tag.slugOf and the search index went through separate folds until #43. If
        // they disagreed, a campaign tagged "İncəsənət" would be filed under one
        // spelling and findable under another, and the filter URL in the facet panel
        // would lead to an empty feed.
        assertThat(az.ideanest.project.domain.Tag.of("İncəsənət").getSlug()).isEqualTo("incesenet");
        assertThat(az.ideanest.project.domain.Tag.of("Əl işi").getSlug()).isEqualTo("el-isi");
        assertThat(new JdbcTemplate(dataSource)
                        .queryForObject("SELECT ideanest_fold(?)", String.class, "İncəsənət"))
                .isEqualTo("incesenet");
    }
}
