package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.support.Campaigns;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /v1/search/suggest}: what a search box offers while it is being typed.
 * D-02, and the endpoint #46's autocomplete reads.
 *
 * <p>The interesting assertions here are not about matching — that is the same fold
 * and the same index as everything else. They are about the three properties a
 * suggestion list has to have and that are easy to get wrong: every row says what
 * kind of thing it is, the list is bounded, and an empty box suggests nothing
 * rather than everything.
 *
 * <p>The UI is #46's. Nothing here presses a key.
 */
class SearchSuggestApiTests extends DiscoveryTestSupport {

    @BeforeEach
    void seedThingsToSuggest() {
        Campaigns.clear(dataSource);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID creator = Campaigns.creator(dataSource, "suggest-creator", "Nigar Quliyeva");

        // Three campaigns whose titles share a prefix, so that the bound and the
        // ordering are both observable, plus one filed under games so a category
        // suggestion and a campaign suggestion answer the same fragment.
        Campaigns.seed(dataSource, creator, "gamer-one")
                .state("LIVE")
                .title("Oyunçular üçün masa")
                .category("games")
                .pledged("100.00")
                .launchedAt(now.minus(3, ChronoUnit.DAYS))
                .deadline(now.plus(10, ChronoUnit.DAYS))
                .tags("oyuncaq")
                .insert();
        Campaigns.seed(dataSource, creator, "gamer-two")
                .state("LIVE")
                .title("Oyun gecəsi dəsti")
                .category("games")
                .pledged("900.00")
                .launchedAt(now.minus(2, ChronoUnit.DAYS))
                .deadline(now.plus(10, ChronoUnit.DAYS))
                .tags("oyuncaq")
                .insert();
        Campaigns.seed(dataSource, creator, "photo")
                .state("LIVE")
                .title("Fotoqrafiya albomu")
                .category("photography")
                .pledged("50.00")
                .launchedAt(now.minus(1, ChronoUnit.DAYS))
                .deadline(now.plus(10, ChronoUnit.DAYS))
                .insert();
        Campaigns.seed(dataSource, creator, "hidden")
                .state("DRAFT")
                .title("Oyun sirri")
                .category("games")
                .insert();
    }

    @Test
    @DisplayName("every suggestion says what kind of thing it is")
    void everyRowCarriesItsKind() {
        List<Map<String, Object>> suggestions = suggestions("?q={q}", "oyun");

        assertThat(suggestions).isNotEmpty();
        for (Map<String, Object> suggestion : suggestions) {
            // Without the kind, a client has a list of strings and no way to tell that
            // "oyuncaq" leads to ?tag=oyuncaq and "Oyun gecəsi dəsti" leads to a
            // project page. It would be able to do exactly one thing with the list:
            // paste it back into the box the reader typed it in.
            assertThat(suggestion).containsKeys("kind", "label", "slug");
            assertThat(suggestion.get("kind"))
                    .isIn("campaign", "category", "subcategory", "tag");
        }
        assertThat(kinds(suggestions)).contains("campaign", "tag");
    }

    @Test
    @DisplayName("suggestions come from campaigns, the taxonomy, and tags")
    void allThreeSourcesAnswer() {
        // "oyun" is the start of two campaign titles, of the tag "oyuncaq", and of
        // nothing in the taxonomy; "foto" is the start of a campaign title and of the
        // Photography category. Between them every source is exercised.
        assertThat(labels(suggestions("?q={q}", "oyun"))).contains("Oyun gecəsi dəsti", "oyuncaq");
        assertThat(kinds(suggestions("?q={q}", "foto"))).contains("campaign", "category");
    }

    @Test
    @DisplayName("no one source can fill the list")
    void thelistIsSharedBetweenKinds() {
        // Round robin rather than concatenation. "oyun" is the start of the Games
        // category ("Oyunlar"), of six of its subcategories, of a tag, and of two
        // campaigns — so under concatenation a list of three would be three
        // subcategories and the reader typing the name of a campaign would be shown
        // everything but it.
        List<Map<String, Object>> suggestions = suggestions("?q={q}&limit=3", "oyun");

        assertThat(suggestions).hasSize(3);
        assertThat(kinds(suggestions)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a fragment is matched with the fold, in both directions")
    void suggestionsFoldToo() {
        // §11.3 again, and it has to hold here too: a suggestion list that only
        // completed the spelling the reader was already using would be no help to the
        // reader who is using the other one.
        assertThat(labels(suggestions("?q={q}", "gecesi"))).contains("Oyun gecəsi dəsti");
        assertThat(labels(suggestions("?q={q}", "gecəsi"))).contains("Oyun gecəsi dəsti");
        assertThat(labels(suggestions("?q={q}", "OYUN"))).contains("Oyun gecəsi dəsti");
    }

    @Test
    @DisplayName("a fragment inside a word matches, with the prefixes first")
    void prefixMatchesComeFirst() {
        // Substring, because a two-word title is usually remembered by its second
        // word. Prefix-first, because a fragment that starts a name is far more likely
        // to be what was meant than one that appears in the middle of one.
        List<String> labels = labels(suggestions("?q={q}&limit=20", "oyun"));

        assertThat(labels).contains("Oyun gecəsi dəsti", "Oyunçular üçün masa");
        assertThat(labels.indexOf("Oyun gecəsi dəsti")).isLessThan(labels.indexOf("Oyunçular üçün masa"));
    }

    @Test
    @DisplayName("a campaign suggestion carries what a link to it needs")
    void campaignSuggestionsCarryTheirCreator() {
        // A project page is /{creatorSlug}/{projectSlug}, so a suggestion with only
        // the campaign's slug would make the client fetch the campaign in order to
        // find out where to send somebody who clicked it.
        Map<String, Object> campaign = suggestions("?q={q}&limit=20", "oyun").stream()
                .filter(suggestion -> "campaign".equals(suggestion.get("kind")))
                .findFirst()
                .orElseThrow();

        assertThat(campaign.get("slug")).isIn("gamer-one", "gamer-two");
        assertThat(campaign.get("parentSlug")).isEqualTo("suggest-creator");
    }

    @Test
    @DisplayName("a subcategory suggestion says which category it belongs to")
    void subcategorySuggestionsCarryTheirParent() {
        // Subcategory slugs are unique within a parent and not globally (V6), so a
        // suggestion carrying only the slug would be ambiguous — "festivals" is filed
        // under both Film and Theatre.
        Map<String, Object> subcategory = suggestions("?q={q}&limit=20", "tabletop").stream()
                .filter(suggestion -> "subcategory".equals(suggestion.get("kind")))
                .findFirst()
                .orElseThrow();

        assertThat(subcategory.get("slug")).isEqualTo("tabletop");
        assertThat(subcategory.get("parentSlug")).isEqualTo("games");
    }

    @Test
    @DisplayName("a campaign the public may not see is never suggested")
    void hiddenCampaignsAreNotSuggested() {
        // A suggestion is a title shown to whoever is typing, so a draft leaking here
        // leaks exactly as much as one leaking into the feed — and more quietly,
        // because nobody is looking at a dropdown for a list of everything.
        assertThat(labels(suggestions("?q={q}&limit=20", "sirri"))).isEmpty();
        assertThat(labels(suggestions("?q={q}&limit=20", "oyun"))).doesNotContain("Oyun sirri");
    }

    @Test
    @DisplayName("an empty box suggests nothing rather than everything")
    void ablankQuerySuggestsNothing() {
        // The state every session starts in. Answering it with the first ten campaigns
        // in the table would put a list under the cursor of somebody who has not asked
        // for one, on every visitor who ever focuses the field.
        assertThat(suggestions("")).isEmpty();
        assertThat(suggestions("?q=")).isEmpty();
        assertThat(suggestions("?q={q}", "   ")).isEmpty();
        // And one character is refused for the same reason with different arithmetic:
        // "o" is a prefix of a large fraction of everything.
        assertThat(suggestions("?q={q}", "o")).isEmpty();
        // 200 with an empty list, not a 400: this is called on every keystroke,
        // including the ones that empty the box, and a client doing that is behaving
        // correctly.
        assertThat(get("/v1/search/suggest", new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the list is bounded, whatever was asked for")
    void theListIsBounded() {
        assertThat(suggestions("?q={q}", "oyun")).hasSizeLessThanOrEqualTo(10);
        assertThat(suggestions("?q={q}&limit=2", "oyun")).hasSize(2);
        // Clamped rather than refused, as the feed's limit is: a client asking for a
        // thousand suggestions wants a list, and twenty is more useful than an error.
        assertThat(suggestions("?q={q}&limit=1000", "oy")).hasSizeLessThanOrEqualTo(20);
        assertThat(suggestions("?q={q}&limit=0", "oyun")).hasSize(1);
        // A limit that is not a number is a different thing, and is refused.
        assertThat(get("/v1/search/suggest?q=oyun&limit=lots", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a fragment matching nothing is an empty list, not an error")
    void nothingMatchingIsEmpty() {
        assertThat(suggestions("?q={q}", "qwertyuiop")).isEmpty();
    }

    @Test
    @DisplayName("suggestions are a public, cacheable read")
    void suggestionsAreCacheable() {
        ResponseEntity<Map<String, Object>> first = get("/v1/search/suggest?q={q}", new HttpHeaders(), "oyun");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getETag()).isNotBlank();
        // Five minutes rather than the feed's one: a suggestion holds no money and
        // changes only when somebody creates a campaign or coins a tag, and it is the
        // most-requested thing here — one request per keystroke.
        assertThat(first.getHeaders().getCacheControl()).contains("max-age=300");
        assertThat(first.getHeaders().getVary()).contains(HttpHeaders.ACCEPT_LANGUAGE);

        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(first.getHeaders().getETag());
        assertThat(get("/v1/search/suggest?q={q}", conditional, "oyun").getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    @DisplayName("a category is suggested by its name in the requested language")
    void categoriesAreSuggestedInTheNegotiatedLanguage() {
        HttpHeaders english = new HttpHeaders();
        english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");

        List<Map<String, Object>> suggestions =
                items(get("/v1/search/suggest?q={q}", english, "photog").getBody(), "items");

        // Taxonomy owns the requested-locale -> az -> slug fallback, and this endpoint
        // borrows it rather than reimplementing the chain in SQL.
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.stream().map(suggestion -> suggestion.get("label")))
                .contains("Photography");
    }

    private static List<String> kinds(List<Map<String, Object>> suggestions) {
        return suggestions.stream().map(suggestion -> (String) suggestion.get("kind")).toList();
    }

    private static List<String> labels(List<Map<String, Object>> suggestions) {
        return suggestions.stream().map(suggestion -> (String) suggestion.get("label")).toList();
    }
}
