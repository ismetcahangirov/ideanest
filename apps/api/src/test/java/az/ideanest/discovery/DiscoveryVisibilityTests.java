package az.ideanest.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.discovery.domain.CompletionBand;
import az.ideanest.discovery.domain.DiscoverySort;
import az.ideanest.discovery.domain.DiscoveryStatus;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.support.Campaigns;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>The most important test in this change.</strong>
 *
 * <p>Discovery is the only endpoint that lists campaigns nobody asked for by name.
 * Everything else in the platform is reached through an identifier somebody already
 * has, and is guarded by an authorisation check written for that one request. This is
 * guarded by a predicate, and a predicate that is wrong is wrong for every campaign at
 * once.
 *
 * <p>What it costs to get wrong is not symmetrical. A filter that returns too few
 * campaigns is a complaint. A filter that returns one too many publishes somebody's
 * unpublished draft, the length of the moderation queue, a rejection, or — worst — a
 * campaign that trust and safety suspended while an investigation is open.
 *
 * <p>So the assertion is not "the status filter works". It is that no combination of
 * filter and sort this API accepts can produce a campaign in one of the seven hidden
 * states of §6.1 — checked against a fixture holding one campaign in every one of the
 * sixteen, under every status filter, every completion band, and every implemented
 * sort.
 */
class DiscoveryVisibilityTests extends DiscoveryTestSupport {

    /** Every state of §6.1, one campaign each, named after the state so a failure says which. */
    private final Map<String, UUID> campaigns = new java.util.LinkedHashMap<>();

    @BeforeEach
    void seedOneCampaignInEveryState() {
        Campaigns.clear(dataSource);
        campaigns.clear();

        UUID creator = Campaigns.creator(dataSource, "visibility-creator");
        Instant launched = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        for (ProjectState state : ProjectState.values()) {
            String slug = state.name().toLowerCase(Locale.ROOT).replace('_', '-');
            campaigns.put(
                    state.name(),
                    Campaigns.seed(dataSource, creator, slug)
                            .state(state.name())
                            .category("games")
                            .goal("1000.00")
                            // Halfway, so the campaign lands in a completion band and
                            // cannot be missed by a band filter for lack of a goal.
                            .pledged("500.00")
                            .backers(5)
                            .launchedAt(launched)
                            .deadline(launched.plus(30, ChronoUnit.DAYS))
                            .tags("visibility")
                            .insert());
        }
        assertThat(campaigns).hasSize(16);
    }

    @Test
    @DisplayName("a draft and a suspended campaign are invisible with no filter at all")
    void theUnfilteredFeedShowsOnlyPublicCampaigns() {
        List<String> returned = slugs(feed("?limit=100"));

        assertThat(returned).doesNotContain("draft", "suspended");
        // And every other hidden state, so this does not become a test about two of
        // the seven.
        assertThat(returned).doesNotContainAnyElementsOf(hiddenSlugs());
        // The nine that may be listed all are, or "nothing is visible" would pass
        // this test just as well as "the right things are visible".
        assertThat(returned).containsAll(publicSlugs());
        assertThat(returned).hasSize(publicSlugs().size());
    }

    @Test
    @DisplayName("no status filter can surface a campaign the public may not see")
    void everyStatusFilterStaysInsideTheVisibleSet() {
        for (DiscoveryStatus status : DiscoveryStatus.values()) {
            List<String> returned = slugs(feed("?limit=100&status=" + status.wireValue()));

            assertThat(returned)
                    .withFailMessage("status=%s returned a hidden campaign: %s", status.wireValue(), returned)
                    .doesNotContainAnyElementsOf(hiddenSlugs());
        }
    }

    @Test
    @DisplayName("no sort order can surface a campaign the public may not see")
    void everySortStaysInsideTheVisibleSet() {
        for (DiscoverySort sort : DiscoverySort.values()) {
            if (sort.requiredCapability().isPresent()) {
                // relevance and near_me are refused outright; that they are refused
                // rather than silently answered is pinned in DiscoveryApiTests.
                continue;
            }
            List<String> returned = slugs(feed("?limit=100&sort=" + sort.wireValue()));

            assertThat(returned)
                    .withFailMessage("sort=%s returned a hidden campaign: %s", sort.wireValue(), returned)
                    .doesNotContainAnyElementsOf(hiddenSlugs());
            assertThat(returned).hasSize(publicSlugs().size());
        }
    }

    @Test
    @DisplayName("no completion band can surface a campaign the public may not see")
    void everyCompletionBandStaysInsideTheVisibleSet() {
        // Every fixture stands at exactly 50%, so one band matches all sixteen rows
        // and the other four match none. That is deliberate: the band that matches is
        // the one with a chance of returning a hidden campaign.
        for (CompletionBand band : CompletionBand.values()) {
            List<String> returned = slugs(feed("?limit=100&completion=" + band.wireValue()));

            assertThat(returned)
                    .withFailMessage("completion=%s returned a hidden campaign: %s", band.wireValue(), returned)
                    .doesNotContainAnyElementsOf(hiddenSlugs());
        }
        assertThat(slugs(feed("?limit=100&completion=50_to_75"))).hasSize(publicSlugs().size());
    }

    @Test
    @DisplayName("no category, tag, or amount filter can surface a campaign the public may not see")
    void everyOtherFilterStaysInsideTheVisibleSet() {
        // All sixteen fixtures share a category, a tag, and their amounts, so each of
        // these filters matches every row in the table and the only thing keeping the
        // hidden seven out is the visibility predicate.
        List<String> queries = List.of(
                "?limit=100&category=games",
                "?limit=100&tag=visibility",
                "?limit=100&goalBand=under_1000",
                "?limit=100&raisedBand=under_1000",
                "?limit=100&goalMin=0&goalMax=100000",
                "?limit=100&raisedMin=0&raisedMax=100000",
                "?limit=100&category=games&tag=visibility&completion=50_to_75&status=live");

        for (String query : queries) {
            assertThat(slugs(feed(query)))
                    .withFailMessage("%s returned a hidden campaign", query)
                    .doesNotContainAnyElementsOf(hiddenSlugs());
        }
    }

    @Test
    @DisplayName("paging to the end of the feed never reaches a hidden campaign")
    void walkingTheWholeFeedNeverReachesOne() {
        // A visibility predicate that was applied to the first page and dropped from
        // the keyset branch would pass every test above and fail here.
        List<String> seen = new ArrayList<>();
        String query = "?limit=2";
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            Map<String, Object> body = feed(query + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(slugs(body));
            cursor = nextCursor(body);
            if (cursor == null) {
                break;
            }
        }
        assertThat(cursor).isNull();
        assertThat(seen).doesNotContainAnyElementsOf(hiddenSlugs());
        assertThat(new LinkedHashSet<>(seen)).containsExactlyInAnyOrderElementsOf(publicSlugs());
    }

    @Test
    @DisplayName("the facet counts do not count a campaign the public may not see")
    void facetsCountOnlyVisibleCampaigns() {
        // A facet is a number, so a leak here is quieter than one in the feed: the
        // suspended campaign is not shown, only counted — and the count is what a
        // reader uses to decide the platform has more than it does.
        Map<String, Object> facets = facets("");

        assertThat(count(facets, "categories", "games")).isEqualTo(publicSlugs().size());
        assertThat(count(facets, "completion", "50_to_75")).isEqualTo(publicSlugs().size());
        assertThat(count(facets, "status", "live")).isEqualTo(1);
        assertThat(count(facets, "tags", "visibility")).isEqualTo(publicSlugs().size());
    }

    private Set<String> hiddenSlugs() {
        return slugsFor(DiscoveryStatus.HIDDEN_STATES);
    }

    private Set<String> publicSlugs() {
        return slugsFor(DiscoveryStatus.PUBLIC_STATES);
    }

    private static Set<String> slugsFor(Set<String> states) {
        Set<String> slugs = new LinkedHashSet<>();
        for (String state : states) {
            slugs.add(state.toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return slugs;
    }
}
