package az.ideanest.discovery.infrastructure;

import az.ideanest.discovery.application.DiscoveryPage;
import az.ideanest.discovery.application.DiscoveryQuery;
import az.ideanest.discovery.application.FacetCounts;
import az.ideanest.discovery.application.SearchService;
import az.ideanest.discovery.application.SuggestQuery;
import az.ideanest.discovery.domain.AmountBand;
import az.ideanest.discovery.domain.AmountRange;
import az.ideanest.discovery.domain.CompletionBand;
import az.ideanest.discovery.domain.DiscoveryCapability;
import az.ideanest.discovery.domain.DiscoveryCursor;
import az.ideanest.discovery.domain.DiscoverySort;
import az.ideanest.discovery.domain.DiscoveryStatus;
import az.ideanest.discovery.domain.InvalidCursorException;
import az.ideanest.discovery.domain.ProjectCard;
import az.ideanest.discovery.domain.Suggestion;
import az.ideanest.project.application.Taxonomy;
import az.ideanest.shared.Money;
import az.ideanest.shared.Slugs;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier 1 of §11.1: discovery served by PostgreSQL.
 *
 * <p><strong>Raw SQL, not the project module's repositories.</strong> Two reasons,
 * and they agree. {@code ModuleBoundaryTests} forbids reaching into
 * {@code project.infrastructure}; and a feed of a hundred cards loaded as JPA
 * entities is a hundred managed objects with their associations, for a projection
 * that uses fourteen columns. §20 asks for a thousand requests a second at p99 under
 * 300ms, and a read model with its own query is the only shape that gets there.
 *
 * <h2>What this can and cannot do</h2>
 *
 * <p>{@link #capabilities()} holds {@link DiscoveryCapability#FULL_TEXT} and nothing
 * else. Every other option §4.3 describes that needs something absent — a
 * {@code locations} table, a saved table, an {@code is_featured} column, a ranking
 * model — is refused by the caller with a problem detail naming its issue, rather
 * than accepted and dropped. See {@link DiscoveryCapability}.
 *
 * <h2>Free text, in two tiers</h2>
 *
 * <p>D-01 is a {@code tsvector} match against {@code projects.search_vector} (V13),
 * over the title, blurb, creator name and story prose, all folded by §11.3 in the
 * database so that the index and the query cannot disagree about what
 * {@code seçənək} means. D-03 — misspelling tolerance — is a second tier over
 * trigram similarity of the title, and it engages <strong>only when the first tier
 * matches nothing at all</strong>: see {@link #exactTierMatches}. An exact match
 * therefore always outranks a fuzzy one, because a fuzzy result set is only ever
 * produced when there is no exact one.
 *
 * <p>Text composes with every filter, every sort, the keyset cursor, and the facet
 * counts, because it is one more AND'd predicate over the same table and nothing
 * else about the query changes. The one thing it adds is an order —
 * {@link DiscoverySort#BEST_MATCH} — whose key is a {@code numeric} rounded to six
 * places, for the reason {@link #POPULARITY_SCORE} is rounded.
 *
 * <h2>What tier 2 would have to satisfy</h2>
 *
 * <p>The list is on {@link SearchService}, where a second implementer will read it.
 * The two that this class earns rather than declares are worth repeating here:
 * visibility is a predicate on a live table, so a campaign suspended a second ago is
 * already invisible — an external index has to be told; and money is
 * {@code numeric} throughout, so a band boundary is exact rather than nearly.
 */
@Service
public class PostgresSearchService implements SearchService {

    /**
     * How coarse the instant that {@link DiscoverySort#POPULARITY} decays against is.
     *
     * <p>Sixty seconds, and deliberately the same as the {@code max-age} the
     * controller sets. A score computed against a continuously moving {@code now()}
     * would give a different response body to two requests a millisecond apart, which
     * defeats the ETag entirely: every conditional request would revalidate to a 200
     * and the cache would buy nothing on the one sort most likely to be a landing
     * page. Truncating to the cache window makes the first page byte-identical for
     * everybody inside it.
     *
     * <p>It also has to be truncated for a second reason. The cursor pins this instant
     * so that page two scores the same campaigns the same way as page one; an instant
     * with millisecond resolution would make every scroll a unique cache key.
     */
    private static final Duration POPULARITY_BUCKET = Duration.ofSeconds(60);

    /**
     * The columns a card needs, and nothing else.
     *
     * <p>{@code users} is joined rather than looked up per row: D-05 puts the creator
     * on the card, and one query per card is the N+1 that a feed cannot afford. The
     * join is inner because {@code projects.creator_id} is {@code NOT NULL} with no
     * {@code ON DELETE} clause — §17.4 anonymises a departing account in place, so the
     * row is always there, and an outer join would only hide a foreign key violation.
     */
    private static final String CARD_COLUMNS =
            """
            p.id, p.slug, p.title, p.state, p.currency,
            p.goal_amount, p.pledged_amount, p.backers_count,
            p.launched_at, p.deadline,
            p.cover_image_url, p.cover_image_width, p.cover_image_height,
            u.name AS creator_name, u.slug AS creator_slug, u.avatar_url AS creator_avatar_url
            """;

    /**
     * Pledge velocity with time decay, as {@code numeric}.
     *
     * <p>Money raised over a power of the campaign's age in hours. The {@code + 2}
     * stops a campaign that launched a moment ago from dividing by nothing, and the
     * exponent of 1.5 is the gravity constant this shape of ranking is usually written
     * with — steep enough that a month-old campaign does not sit on the front page for
     * ever, shallow enough that a campaign has more than a day to be noticed.
     *
     * <p>{@code numeric} rather than {@code double precision} at every step. The value
     * is compared for exact equality by the keyset predicate, and a float that two
     * evaluations of the same expression round differently would make a scroll skip a
     * row. Rounding to six places is what makes that equality reliable rather than
     * merely likely.
     *
     * <p><strong>{@code a * sqrt(a)} rather than {@code power(a, 1.5)}, and the age in
     * whole hours.</strong> They are the same number and they are not the same cost:
     * {@code numeric_power} with a fractional exponent goes through {@code ln} and
     * {@code exp} at full numeric precision, which measured at roughly sixteen
     * microseconds per row — and the score is projected for every matching row before
     * the sort, so at twenty thousand campaigns that alone was 230ms of a 300ms budget
     * (§20). {@code sqrt} is Newton's method on a numeric and is an order of magnitude
     * cheaper, and flooring the age to an hour keeps its input a small integer instead
     * of a fraction with fifteen decimal places. Both stay exact, which
     * {@code double precision} would not.
     *
     * <p>Flooring the age also makes the score change hourly rather than continuously,
     * which is the right granularity for something described as decay and is one less
     * reason for two requests in one cache window to differ.
     *
     * <p>A campaign that never launched scores zero rather than null, so the order has
     * no null branch to handle.
     */
    private static final String POPULARITY_SCORE =
            """
            CASE WHEN p.launched_at IS NULL THEN 0::numeric ELSE round(
                p.pledged_amount / (
                    (floor(greatest(extract(epoch FROM (:asOf - p.launched_at))::numeric, 0) / 3600) + 2)
                    * sqrt(floor(greatest(extract(epoch FROM (:asOf - p.launched_at))::numeric, 0) / 3600) + 2)
                ), 6)
            END
            """;

    /**
     * The reader's words, folded and parsed into a query. §11.3 and D-01.
     *
     * <p><strong>Folded here rather than in Java</strong> — {@code ideanest_fold} is
     * the same function the index was built with, so the two cannot drift. Folding
     * the query in the application and the document in the database is the failure
     * that produces an empty result set and a 200, which looks exactly like "nothing
     * matched".
     *
     * <p>{@code websearch_to_tsquery} rather than {@code plainto_} or
     * {@code to_tsquery}: it is the only one that takes what a person types into a
     * search box. Punctuation cannot make it throw, quoted phrases mean phrases,
     * {@code or} means or, and a leading {@code -} excludes. {@code to_tsquery} would
     * refuse "robot!" outright, and refusing a search because of an exclamation mark
     * is not a thing to explain to anybody.
     *
     * <p><strong>No prefix expansion.</strong> "robo" does not match "robot" through
     * this: {@code websearch_to_tsquery} produces whole lexemes. Partial words are
     * what {@code /v1/search/suggest} is for (D-02), and near-misses are what the
     * trigram tier below is for (D-03). Rewriting the last term as {@code lexeme:*}
     * would mean building a tsquery string out of user input by concatenation, which
     * is the one shape of this problem that has an injection in it.
     */
    private static final String TEXT_QUERY = "websearch_to_tsquery('simple', ideanest_fold(:text))";

    /**
     * How alike a title has to be to a misspelt query before it is offered. D-03.
     *
     * <p><strong>0.4, and it was measured rather than guessed.</strong>
     * {@code word_similarity(query, title)} rather than {@code similarity} because
     * the second compares whole strings: "robot" against "The Amazing Robot Factory
     * Kit" scores 0.19 as a whole string and 1.00 as a word inside one, and a
     * threshold set for the first would only ever match campaigns with one-word
     * titles.
     *
     * <p>Measured on postgres:16-alpine against realistic Azerbaijani titles, folded:
     *
     * <table>
     *   <caption>word_similarity of a query against a title</caption>
     *   <tr><th>query</th><th>title</th><th>what it is</th><th>score</th></tr>
     *   <tr><td>secenekk</td><td>Seçənək masa oyunu</td><td>one letter added</td><td>0.778</td></tr>
     *   <tr><td>secenk</td><td>Seçənək masa oyunu</td><td>one letter dropped</td><td>0.714</td></tr>
     *   <tr><td>bagce</td><td>Gözəl bağça</td><td>one letter substituted</td><td>0.667</td></tr>
     *   <tr><td>robto</td><td>Robot dostum</td><td>two letters transposed</td><td>0.500</td></tr>
     *   <tr><td>sesenek</td><td>Seçənək masa oyunu</td><td>one letter substituted</td><td>0.455</td></tr>
     *   <tr><td>musiqi</td><td>İşıqlı şəhər</td><td><em>unrelated word</em></td><td>0.143</td></tr>
     *   <tr><td>velosiped</td><td>Gözəl bağça</td><td><em>unrelated word</em></td><td>0.000</td></tr>
     * </table>
     *
     * <p>Every single-character error measured lands at 0.455 or above and every
     * unrelated word at 0.143 or below, so the honest threshold is anywhere in that
     * gap. 0.4 is chosen inside it and nearer the typo end: it is three times the
     * observed noise floor, so a query that resembles nothing returns nothing, which
     * is the failure this tier must not have — a search box that answers gibberish
     * with a page of unrelated campaigns is worse than one that answers it with
     * nothing, because the reader cannot tell that it did not understand.
     *
     * <p>It is not {@code pg_trgm}'s own 0.6 default, which is set for deduplication
     * rather than for typing, and which would reject four of the five errors above.
     */
    private static final String FUZZY_THRESHOLD = "0.4";

    private final NamedParameterJdbcTemplate jdbc;
    private final Taxonomy taxonomy;
    private final Clock clock;

    public PostgresSearchService(NamedParameterJdbcTemplate jdbc, Taxonomy taxonomy, Clock clock) {
        this.jdbc = jdbc;
        this.taxonomy = taxonomy;
        this.clock = clock;
    }

    /**
     * Free text, and nothing else yet. See the class comment and
     * {@link DiscoveryCapability}.
     *
     * <p>The set is the whole of this class's public promise, and it is a claim the
     * tests hold it to: a capability announced here without an implementation behind
     * it fails the suite that pins each refusal, and one implemented without being
     * announced is refused by the controller and never reached.
     */
    @Override
    public Set<DiscoveryCapability> capabilities() {
        return EnumSet.of(DiscoveryCapability.FULL_TEXT);
    }

    @Override
    @Transactional(readOnly = true)
    public DiscoveryPage search(DiscoveryQuery query) {
        Instant asOf = asOf(query);
        boolean exactTier = exactTierMatches(query.text());
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("asOf", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));

        List<String> where = new ArrayList<>();
        where.add(statePredicate("p", DiscoveryStatus.statesFor(query.statuses()), params));
        add(where, textPredicate("p", query, exactTier, params));
        filters("p", query, where, params);

        DiscoveryCursor cursor = query.cursor();
        if (cursor != null) {
            cursor.requireMatches(query.fingerprint(), query.sort());
            where.add(keyset(query.sort(), cursor, exactTier, params));
        }

        // One more row than asked for. It is not returned; its existence is the only
        // reliable answer to "is there a next page", and asking for it costs one row
        // rather than the COUNT(*) over the whole predicate that the alternative
        // would run on every page of every scroll.
        params.addValue("limit", query.limit() + 1);

        String sql = "SELECT " + CARD_COLUMNS + ", " + scoreColumn(query.sort(), exactTier)
                + " FROM projects p JOIN users u ON u.id = p.creator_id"
                + " WHERE " + String.join(" AND ", where)
                + " ORDER BY " + orderBy(query.sort())
                + " LIMIT :limit";

        List<Row> rows = jdbc.query(sql, params, (resultSet, index) -> toRow(resultSet, asOf));

        boolean hasMore = rows.size() > query.limit();
        List<Row> page = hasMore ? rows.subList(0, query.limit()) : rows;
        List<ProjectCard> items = page.stream().map(Row::card).toList();

        DiscoveryCursor next = null;
        if (hasMore && !page.isEmpty()) {
            Row last = page.get(page.size() - 1);
            next = new DiscoveryCursor(
                    query.fingerprint(), query.sort(), sortKey(query.sort(), last), last.card().id(), asOf);
        }
        return new DiscoveryPage(items, next);
    }

    /**
     * Every facet in one round trip.
     *
     * <h2>Shape, and what it costs</h2>
     *
     * <p>A facet that excludes its own dimension cannot share a {@code WHERE} clause
     * with the others, so the obvious implementation is one query per dimension —
     * seven round trips for one panel. Instead a single {@code base} CTE scans the
     * publicly visible campaigns once and tags each row with a boolean per dimension
     * saying whether it matches that dimension's filter. Every facet is then an
     * aggregate over that one materialised set, requiring all the flags except its
     * own, and the seven aggregates are stitched together with {@code UNION ALL} into
     * one statement.
     *
     * <p><strong>Cost.</strong> One pass over the publicly visible campaigns to build
     * {@code base}, plus one hash aggregate per dimension over it, plus one join to
     * {@code project_tags} for the tag facet. Everything the aggregates join against —
     * fifteen categories, a hundred and five subcategories, five bands twice, five
     * statuses — is tiny and static. {@code MATERIALIZED} is explicit rather than left
     * to the planner, because the CTE is referenced seven times and inlining it would
     * turn one scan into seven.
     *
     * <p><strong>Where the ceiling is.</strong> {@code base} is O(public campaigns)
     * and no index removes that: a count over every matching row has to touch every
     * matching row. The partial covering index in V12 keeps it an index-only scan, so
     * the constant is small, but the shape is linear. At §11.1's tier-1 ceiling of
     * roughly ten thousand campaigns this is a few milliseconds. The first thing to do
     * when it stops being is <em>not</em> another index — it is to cache the panel per
     * filter set, because a filter panel changes far less often than a feed page. The
     * second is tier 2, which is exactly the trigger §11.1 names: "or when faceting
     * becomes complex".
     *
     * <p><strong>The one thing that is not in this query</strong> is the localised
     * name of each taxon. Those come from {@link Taxonomy}, which owns the
     * requested-locale → {@code az} → slug fallback chain and would otherwise be
     * reimplemented here in SQL — four small queries against a static tree, against
     * one place that decides what a category is called.
     */
    @Override
    @Transactional(readOnly = true)
    public FacetCounts facets(DiscoveryQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("asOf", OffsetDateTime.ofInstant(asOf(query), ZoneOffset.UTC));

        // The base set is every publicly visible campaign, NOT the status-filtered
        // one: the status facet has to be able to count the statuses the caller did
        // not select, which is the whole point of excluding a dimension from its own
        // counts.
        //
        // Free text narrows the base set rather than being a dimension of its own,
        // because there is no facet control for the search box. A panel counted over
        // every campaign on the platform beside a list of the four that match the
        // query would be describing a different result set from the one on screen —
        // see SearchService.facets.
        String publicStates = and(
                statePredicate("p", DiscoveryStatus.PUBLIC_STATES, params),
                textPredicate("p", query, exactTierMatches(query.text()), params));

        String matchesStatus = query.statuses().isEmpty()
                ? "true"
                : statePredicate("p", DiscoveryStatus.statesFor(query.statuses()), params);
        // Category and subcategory are one dimension for exclusion, not two. A caller
        // who selected "games / tabletop" and was shown category counts computed under
        // the subcategory filter would see zero beside every other category, because
        // no campaign outside Games is filed under Tabletop — the same dead end that
        // excluding the dimension exists to prevent, arriving through the back door.
        String matchesTaxonomy = and(
                categoryPredicate("p", query.categorySlugs(), params),
                subcategoryPredicate("p", query.subcategorySlugs(), params));
        String matchesTags = tagPredicate("p", query.tagSlugs(), params);
        String matchesCompletion = completionPredicate("p", query.completion(), params);
        String matchesGoal = amountPredicate("p.goal_amount", "goalFacet", query.goal(), params);
        String matchesRaised = amountPredicate("p.pledged_amount", "raisedFacet", query.raised(), params);

        String sql = facetSql(
                publicStates, matchesStatus, matchesTaxonomy, matchesTags,
                matchesCompletion, matchesGoal, matchesRaised, params);

        List<FacetRow> rows = jdbc.query(sql, params, (resultSet, index) -> new FacetRow(
                resultSet.getString("dimension"),
                resultSet.getString("key"),
                resultSet.getString("extra"),
                resultSet.getLong("n")));

        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        Map<String, String> tagLabels = new LinkedHashMap<>();
        List<String> tagOrder = new ArrayList<>();
        for (FacetRow row : rows) {
            if ("tag".equals(row.dimension())) {
                tagLabels.put(row.key(), row.extra());
                tagOrder.add(row.key());
            }
            // Subcategory slugs are unique per parent and not globally, so a
            // subcategory's key is the pair. Joining them with a character that
            // cannot occur in a slug keeps one flat map rather than a special case.
            String composite = row.extra() == null || "tag".equals(row.dimension())
                    ? row.key()
                    : row.key() + "/" + row.extra();
            counts.computeIfAbsent(row.dimension(), ignored -> new LinkedHashMap<>())
                    .put(composite, row.count());
        }

        return assemble(query.locale(), counts, tagLabels, tagOrder);
    }

    // ---------------------------------------------------------------------------
    // Suggestions (D-02)
    // ---------------------------------------------------------------------------

    /**
     * What the reader might mean, from three sources at once.
     *
     * <h2>How a fragment matches</h2>
     *
     * <p>Substring of the folded form, with the prefix matches first. Substring
     * rather than prefix alone because a two-word title is usually remembered by its
     * second word — somebody typing "oyun" is looking for "Seçənək masa oyunu" — and
     * prefix-first because a fragment that starts a name is far more likely to be
     * what was meant than one that appears in the middle of it.
     *
     * <p>{@code LIKE} over {@code ideanest_fold(title)} rather than the
     * {@code tsvector}: a tsvector holds whole lexemes, so "seç" matches nothing in
     * it, and matching a partial word is the entire job here. The trigram index V13
     * creates on that expression is what makes the wildcard-leading pattern an index
     * scan rather than a table scan.
     *
     * <h2>How the three sources share the list</h2>
     *
     * <p>Round robin, in the order category, subcategory, tag, campaign, until the
     * limit is reached. Not concatenation: a fragment matching six categories would
     * otherwise fill a list of ten before a single campaign appeared, and the reader
     * typing the name of a campaign would be shown everything except it. Round robin
     * guarantees every kind that matched at all is represented, which is what makes
     * {@link Suggestion.Kind} worth carrying.
     *
     * <p>The taxonomy is matched in memory rather than in SQL. It is fifteen
     * categories and about a hundred subcategories, it is already loaded per request
     * for the facet labels, and {@link Taxonomy} owns the requested-locale → az →
     * slug fallback chain — reimplementing that chain in a {@code LIKE} over
     * {@code category_translations} would be a second answer to "what is this
     * category called".
     */
    @Override
    @Transactional(readOnly = true)
    public List<Suggestion> suggest(SuggestQuery query) {
        if (!query.isAnswerable()) {
            // A blank or one-character box suggests nothing rather than everything.
            // See SuggestQuery.isAnswerable.
            return List.of();
        }

        String fragment = Slugs.fold(query.text());
        List<Suggestion> categories = new ArrayList<>();
        List<Suggestion> subcategories = new ArrayList<>();
        for (Taxonomy.TaxonomyCategory category : taxonomy.all(query.locale())) {
            if (matches(fragment, category.name(), category.slug())) {
                categories.add(new Suggestion(
                        Suggestion.Kind.CATEGORY, category.name(), category.slug(), null));
            }
            for (Taxonomy.TaxonomySubcategory subcategory : category.subcategories()) {
                if (matches(fragment, subcategory.name(), subcategory.slug())) {
                    subcategories.add(new Suggestion(
                            Suggestion.Kind.SUBCATEGORY, subcategory.name(), subcategory.slug(), category.slug()));
                }
            }
        }

        return interleave(
                query.limit(),
                List.of(categories, subcategories, tagSuggestions(query), campaignSuggestions(query)));
    }

    /** A folded fragment inside a taxon's localised name or its slug. */
    private static boolean matches(String fragment, String name, String slug) {
        return Slugs.fold(name).contains(fragment) || slug.contains(fragment);
    }

    private List<Suggestion> tagSuggestions(SuggestQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("fragment", likePattern(query.text()));
        params.addValue("limit", query.limit());
        // Label and slug both, because they differ by exactly the fold: a reader
        // typing "ceramics" has to find the tag whose label is "Seramika" through its
        // slug, and one typing "Sər" has to find it through its label.
        return jdbc.query(
                """
                SELECT t.slug, t.label,
                       (ideanest_fold(t.label) LIKE ideanest_fold(:fragment) || '%' ESCAPE '\\'
                        OR t.slug LIKE ideanest_fold(:fragment) || '%' ESCAPE '\\') AS is_prefix
                  FROM tags t
                 WHERE ideanest_fold(t.label) LIKE '%' || ideanest_fold(:fragment) || '%' ESCAPE '\\'
                    OR t.slug LIKE '%' || ideanest_fold(:fragment) || '%' ESCAPE '\\'
                 ORDER BY is_prefix DESC, t.usage_count DESC, t.slug ASC
                 LIMIT :limit
                """,
                params,
                (resultSet, index) -> new Suggestion(
                        Suggestion.Kind.TAG, resultSet.getString("label"), resultSet.getString("slug"), null));
    }

    private List<Suggestion> campaignSuggestions(SuggestQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("fragment", likePattern(query.text()));
        params.addValue("limit", query.limit());
        params.addValue("states", List.copyOf(DiscoveryStatus.PUBLIC_STATES));
        // The same visibility predicate as everything else in this class, and for the
        // same reason: a suggestion is a campaign's title shown to whoever is typing,
        // so a draft leaking here leaks exactly as much as one leaking into the feed.
        //
        // Ordered by money raised after the prefix rule, as the closest thing to
        // prominence that exists today — a suggestion list is ten rows and something
        // has to decide which ten. `id` last, so the order is total and two runs of
        // the same keystroke do not reshuffle the dropdown under the reader's finger.
        return jdbc.query(
                """
                SELECT p.slug, p.title, u.slug AS creator_slug,
                       (ideanest_fold(p.title) LIKE ideanest_fold(:fragment) || '%' ESCAPE '\\') AS is_prefix
                  FROM projects p
                  JOIN users u ON u.id = p.creator_id
                 WHERE p.state IN (:states)
                   AND ideanest_fold(p.title) LIKE '%' || ideanest_fold(:fragment) || '%' ESCAPE '\\'
                 ORDER BY is_prefix DESC, p.pledged_amount DESC, p.id ASC
                 LIMIT :limit
                """,
                params,
                (resultSet, index) -> new Suggestion(
                        Suggestion.Kind.CAMPAIGN,
                        resultSet.getString("title"),
                        resultSet.getString("slug"),
                        resultSet.getString("creator_slug")));
    }

    /**
     * The reader's fragment as a {@code LIKE} operand.
     *
     * <p>{@code %}, {@code _} and {@code \} are wildcards to {@code LIKE} and
     * ordinary characters to a person: without this, typing {@code %} into the search
     * box would match every campaign on the platform, and typing {@code _} would match
     * every one whose title is one character longer than the fragment. Escaped here
     * rather than folded away because none of the three is touched by {@code ə→e}.
     *
     * <p>Escaped in Java and folded in SQL, so that the fold still has exactly one
     * implementation.
     */
    private static String likePattern(String fragment) {
        return fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * One suggestion from each source in turn, until there are enough.
     *
     * <p>See {@link #suggest}: the alternative is that the first source fills the
     * list.
     */
    private static List<Suggestion> interleave(int limit, List<List<Suggestion>> sources) {
        List<Suggestion> merged = new ArrayList<>();
        int deepest = sources.stream().mapToInt(List::size).max().orElse(0);
        for (int depth = 0; depth < deepest && merged.size() < limit; depth++) {
            for (List<Suggestion> source : sources) {
                if (merged.size() >= limit) {
                    break;
                }
                if (depth < source.size()) {
                    merged.add(source.get(depth));
                }
            }
        }
        return List.copyOf(merged);
    }

    // ---------------------------------------------------------------------------
    // Filters
    // ---------------------------------------------------------------------------

    /**
     * The predicates a search applies, in the order they narrow hardest first.
     *
     * <p>Every one of them composes with every other because they are AND'd clauses
     * over one table; there is no combination of §4.3's filters that this cannot
     * express, which is what "combinable" in the issue means.
     */
    private static void filters(
            String alias, DiscoveryQuery query, List<String> where, MapSqlParameterSource params) {
        add(where, categoryPredicate(alias, query.categorySlugs(), params));
        add(where, subcategoryPredicate(alias, query.subcategorySlugs(), params));
        add(where, tagPredicate(alias, query.tagSlugs(), params));
        add(where, completionPredicate(alias, query.completion(), params));
        add(where, amountPredicate(alias + ".goal_amount", "goal", query.goal(), params));
        add(where, amountPredicate(alias + ".pledged_amount", "raised", query.raised(), params));
    }

    /**
     * D-01 and D-03: the free-text clause, in whichever tier is live.
     *
     * <p>One AND'd predicate over the same table as every other filter, which is what
     * makes "text composes with everything" true rather than aspirational — there is
     * no separate query, no second result set, and nothing to merge.
     *
     * <p>Only one of the two tiers is ever in the SQL. The alternative — emitting
     * both, OR'd, and letting the fuzzy branch pick up what the exact one missed —
     * was measured and rejected: wrapping the two in a {@code CASE} or an {@code OR}
     * makes the whole predicate opaque to the planner, and the plan drops from a
     * bitmap index scan on {@code projects_search_vector_idx} to a sequential scan of
     * the table. The tier is therefore decided before the statement is built, by
     * {@link #exactTierMatches}, and the hot path is left as the single indexable
     * expression it needs to be.
     *
     * @param exactTier from {@link #exactTierMatches}; ignored when there is no text
     */
    private static String textPredicate(
            String alias, DiscoveryQuery query, boolean exactTier, MapSqlParameterSource params) {
        if (query.text() == null) {
            return "true";
        }
        params.addValue("text", query.text());
        if (exactTier) {
            return alias + ".search_vector @@ " + TEXT_QUERY;
        }
        // D-03. The title only: a typo is something a person makes while typing the
        // name of the thing they are looking for, and trigram-matching several
        // thousand words of story would be both expensive and noisy.
        return "word_similarity(ideanest_fold(:text), ideanest_fold(" + alias + ".title)) >= " + FUZZY_THRESHOLD;
    }

    /**
     * The score {@link DiscoverySort#BEST_MATCH} orders by, as {@code numeric}.
     *
     * <p>{@code ts_rank} in the exact tier, with PostgreSQL's default weights
     * {@code {D, C, B, A} = {0.1, 0.2, 0.4, 1.0}} — so a title match scores ten times
     * a story match, which is the whole reason V13 sets weights at all. Trigram
     * similarity in the fuzzy tier, which is the only thing there is to rank by when
     * nothing matched exactly.
     *
     * <p><strong>Rounded to six places and cast to {@code numeric}.</strong> Both
     * functions return {@code real}. The keyset predicate compares the cursor's key to
     * this expression for exact equality, and two evaluations of a float expression
     * that round differently would make a scroll skip a row — the same hazard, and
     * the same fix, as {@link #POPULARITY_SCORE}.
     */
    private static String textScore(String alias, boolean exactTier) {
        return exactTier
                ? "round(ts_rank(" + alias + ".search_vector, " + TEXT_QUERY + ")::numeric, 6)"
                : "round(word_similarity(ideanest_fold(:text), ideanest_fold(" + alias + ".title))::numeric, 6)";
    }

    /**
     * Whether the exact tier finds anything at all, which is what decides D-03.
     *
     * <p><strong>When the fallback engages.</strong> Only here: if the folded query
     * matches the search vector of any publicly visible campaign, the search is served
     * from the {@code tsvector} index and nothing pays for trigrams. If it matches
     * none, and only then, the whole query is re-expressed as a similarity filter. A
     * search that works is therefore never slowed down by a feature that exists for
     * searches that do not.
     *
     * <p>The cost of asking is one probe of {@code projects_search_vector_idx} with a
     * {@code LIMIT 1} behind it, inside the same read-only transaction as the query
     * that follows.
     *
     * <p><strong>It deliberately ignores the caller's filters.</strong> The question
     * is "is this word spelled like something on the platform", not "does this
     * combination of filters match anything" — so {@code ?q=robto&category=games}
     * corrects the spelling and then honestly returns nothing if no games campaign
     * matches. Asking the filtered question instead would turn every over-narrow
     * filter set into a fuzzy search, and the reader who ticked four filters and got
     * approximate matches back would have no way to tell which of the two happened.
     *
     * <p><strong>And it is asked again on every page.</strong> The tier is a function
     * of the query and the data, not of the page, so page two agrees with page one
     * unless a campaign matching the term exactly appeared or disappeared in between —
     * in which case the result set changed anyway, which is the condition every keyset
     * cursor on this platform already lives with.
     *
     * @param text null when the caller sent none, in which case there is no tier and
     *     the answer is unused
     */
    private boolean exactTierMatches(String text) {
        if (text == null) {
            return true;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("text", text);
        params.addValue("states", List.copyOf(DiscoveryStatus.PUBLIC_STATES));
        Boolean matched = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM projects p WHERE p.state IN (:states)"
                        + " AND p.search_vector @@ " + TEXT_QUERY + ")",
                params,
                Boolean.class);
        return Boolean.TRUE.equals(matched);
    }

    /**
     * The only clause that is never optional.
     *
     * <p>{@code DiscoveryStatus.statesFor} has already intersected whatever was asked
     * for with the nine states the public may see, so this cannot widen past them
     * however the argument was built.
     */
    private static String statePredicate(String alias, Set<String> states, MapSqlParameterSource params) {
        String name = "states" + params.getValues().size();
        params.addValue(name, List.copyOf(states));
        return alias + ".state IN (:" + name + ")";
    }

    private static String categoryPredicate(String alias, Set<String> slugs, MapSqlParameterSource params) {
        if (slugs.isEmpty()) {
            return "true";
        }
        String name = "categorySlugs" + params.getValues().size();
        params.addValue(name, List.copyOf(slugs));
        // By slug rather than by identifier, because a slug is what is in the URL and
        // in every fixture; V6 says so on the column. A slug nobody has matches
        // nothing, which is the right answer to a filter for a category that does not
        // exist — it is not an error, because a client following a stale link should
        // see an empty feed rather than a 400.
        return alias + ".category_id IN (SELECT id FROM categories WHERE slug IN (:" + name + "))";
    }

    private static String subcategoryPredicate(String alias, Set<String> slugs, MapSqlParameterSource params) {
        if (slugs.isEmpty()) {
            return "true";
        }
        String name = "subcategorySlugs" + params.getValues().size();
        params.addValue(name, List.copyOf(slugs));
        // Subcategory slugs are unique within a parent and not globally (V6), so
        // "festivals" names one row under Film and another under Theatre and this
        // matches both. That is the honest reading of a filter by slug, and a caller
        // who meant one of them sends the category too — the two clauses are AND'd.
        return alias + ".subcategory_id IN (SELECT id FROM subcategories WHERE slug IN (:" + name + "))";
    }

    /**
     * Every named tag, not any of them. See {@code DiscoveryQuery} for why AND.
     *
     * <p>One counting subquery rather than one {@code EXISTS} per tag: the count of
     * matching edges equals the number of tags asked for exactly when the campaign
     * carries all of them, and {@code project_tags} has the pair as its primary key so
     * an edge cannot be counted twice. The probe is by {@code (project_id, tag_id)},
     * which is that primary key.
     */
    private static String tagPredicate(String alias, Set<String> slugs, MapSqlParameterSource params) {
        if (slugs.isEmpty()) {
            return "true";
        }
        String name = "tagSlugs" + params.getValues().size();
        params.addValue(name, List.copyOf(slugs));
        params.addValue(name + "Count", slugs.size());
        return "(SELECT count(*) FROM project_tags pt JOIN tags t ON t.id = pt.tag_id"
                + " WHERE pt.project_id = " + alias + ".id AND t.slug IN (:" + name + ")) = :" + name + "Count";
    }

    /**
     * The completion bands, OR'd, compared without dividing.
     *
     * <p>{@code pledged × 100 ≥ goal × lower} rather than {@code pledged / goal ≥ …}:
     * {@code numeric} multiplication is exact, division is not, and
     * {@code projects_goal_is_positive} guarantees the multiplier is positive so the
     * direction of the comparison is preserved. A campaign with no goal is excluded
     * rather than treated as zero percent — it has not asked for anything yet.
     */
    private static String completionPredicate(
            String alias, Set<CompletionBand> bands, MapSqlParameterSource params) {
        if (bands.isEmpty()) {
            return "true";
        }
        List<String> clauses = new ArrayList<>();
        for (CompletionBand band : bands) {
            String prefix = "completion" + band.name() + params.getValues().size();
            params.addValue(prefix + "Lo", band.lowerInclusive());
            String clause = alias + ".pledged_amount * 100 >= " + alias + ".goal_amount * :" + prefix + "Lo";
            if (band.upperExclusive() != null) {
                params.addValue(prefix + "Hi", band.upperExclusive());
                clause += " AND " + alias + ".pledged_amount * 100 < " + alias + ".goal_amount * :" + prefix + "Hi";
            }
            clauses.add("(" + clause + ")");
        }
        return "(" + alias + ".goal_amount IS NOT NULL AND (" + String.join(" OR ", clauses) + "))";
    }

    /** One money dimension: bands OR'd with each other, AND'd with the custom range. */
    private static String amountPredicate(
            String column, String prefix, AmountRange range, MapSqlParameterSource params) {
        if (range.isAny()) {
            return "true";
        }
        List<String> clauses = new ArrayList<>();
        if (!range.bands().isEmpty()) {
            List<String> bandClauses = new ArrayList<>();
            for (AmountBand band : range.bands()) {
                String name = prefix + band.name() + params.getValues().size();
                params.addValue(name + "Lo", band.lowerInclusive());
                String clause = column + " >= :" + name + "Lo";
                if (band.upperExclusive() != null) {
                    params.addValue(name + "Hi", band.upperExclusive());
                    clause += " AND " + column + " < :" + name + "Hi";
                }
                bandClauses.add("(" + clause + ")");
            }
            clauses.add("(" + String.join(" OR ", bandClauses) + ")");
        }
        if (range.minInclusive() != null) {
            String name = prefix + "Min" + params.getValues().size();
            params.addValue(name, range.minInclusive());
            clauses.add(column + " >= :" + name);
        }
        if (range.maxInclusive() != null) {
            String name = prefix + "Max" + params.getValues().size();
            params.addValue(name, range.maxInclusive());
            clauses.add(column + " <= :" + name);
        }
        return "(" + String.join(" AND ", clauses) + ")";
    }

    // ---------------------------------------------------------------------------
    // Ordering and the keyset
    // ---------------------------------------------------------------------------

    /**
     * The order, always ending in {@code id}.
     *
     * <p>{@code NULLS LAST} is written out on both orders that can have a null key.
     * PostgreSQL's default is {@code NULLS FIRST} for {@code DESC} and
     * {@code NULLS LAST} for {@code ASC}, so leaving it implicit would put every
     * campaign that has not launched at the top of the newest feed — and, worse, would
     * disagree with the index in V12, which spells it out.
     */
    private static String orderBy(DiscoverySort sort) {
        return switch (sort) {
            case NEWEST -> "p.launched_at DESC NULLS LAST, p.id ASC";
            case ENDING_SOON -> "p.deadline ASC NULLS LAST, p.id ASC";
            case MOST_FUNDED -> "p.pledged_amount DESC, p.id ASC";
            case MOST_BACKED -> "p.backers_count DESC, p.id ASC";
            // Both orders whose key is an expression rather than a column read it
            // from the projected alias, so the score is computed once per row.
            case POPULARITY, BEST_MATCH -> "sort_score DESC, p.id ASC";
            case RELEVANCE, NEAR_ME -> throw new IllegalStateException(
                    // Unreachable: the controller refuses these before they get here,
                    // through the capability check. Throwing rather than falling back
                    // to another order is what keeps that true — a default branch here
                    // would silently make `sort=relevance` mean `sort=newest` the day
                    // somebody removed the check.
                    sort.wireValue() + " is refused before it reaches the query");
        };
    }

    /** {@code sort_score} exists on every query so the row mapper has one shape. */
    private static String scoreColumn(DiscoverySort sort, boolean exactTier) {
        return switch (sort) {
            case POPULARITY -> "(" + POPULARITY_SCORE + ") AS sort_score";
            case BEST_MATCH -> "(" + textScore("p", exactTier) + ") AS sort_score";
            case NEWEST, ENDING_SOON, MOST_FUNDED, MOST_BACKED, RELEVANCE, NEAR_ME ->
                "NULL::numeric AS sort_score";
        };
    }

    /**
     * "Everything after this row, in this order."
     *
     * <p>Three shapes, because two of the orders can have a null key and are sorted
     * nulls-last. For those, a cursor whose key is null means the scroll has reached
     * the null tail, and the only rows still to come are other null-keyed rows with a
     * larger id; a cursor whose key is not null means everything strictly after that
     * key, plus the whole null tail, plus the ties broken by id.
     *
     * <p>Getting this wrong is not a slow feed, it is a wrong one: dropping the
     * {@code IS NULL} branch loses every unlaunched campaign from the second page
     * onwards, and dropping the {@code id} branch loses every row that shares a key
     * with the last row of the previous page.
     */
    private static String keyset(
            DiscoverySort sort, DiscoveryCursor cursor, boolean exactTier, MapSqlParameterSource params) {
        params.addValue("cursorId", cursor.id());
        String key = cursor.sortKey();
        return switch (sort) {
            case NEWEST -> nullableKeyset("p.launched_at", "<", instant(key), params);
            case ENDING_SOON -> nullableKeyset("p.deadline", ">", instant(key), params);
            case MOST_FUNDED -> presentKeyset("p.pledged_amount", decimal(key), params);
            case MOST_BACKED -> presentKeyset("p.backers_count", integer(key), params);
            case POPULARITY -> presentKeyset("(" + POPULARITY_SCORE + ")", decimal(key), params);
            // The score is repeated here rather than referenced as `sort_score`: a
            // SELECT alias is not visible in the WHERE clause, which is evaluated
            // first. Both copies come from textScore, so they cannot disagree.
            case BEST_MATCH -> presentKeyset("(" + textScore("p", exactTier) + ")", decimal(key), params);
            case RELEVANCE, NEAR_ME -> throw new IllegalStateException(
                    sort.wireValue() + " is refused before it reaches the query");
        };
    }

    private static String nullableKeyset(
            String column, String comparison, Object key, MapSqlParameterSource params) {
        if (key == null) {
            return "(" + column + " IS NULL AND p.id > :cursorId)";
        }
        params.addValue("cursorKey", key);
        return "(" + column + " " + comparison + " :cursorKey"
                + " OR " + column + " IS NULL"
                + " OR (" + column + " = :cursorKey AND p.id > :cursorId))";
    }

    private static String presentKeyset(String column, Object key, MapSqlParameterSource params) {
        if (key == null) {
            // A cursor for an order whose key is NOT NULL, carrying no key, is a
            // cursor this service did not write.
            throw InvalidCursorException.undecodable(
                    "That cursor is not a cursor this service issued.");
        }
        params.addValue("cursorKey", key);
        return "(" + column + " < :cursorKey OR (" + column + " = :cursorKey AND p.id > :cursorId))";
    }

    private static OffsetDateTime instant(String key) {
        if (key == null) {
            return null;
        }
        try {
            return OffsetDateTime.ofInstant(Instant.parse(key), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw InvalidCursorException.undecodable(
                    "That cursor is not a cursor this service issued.");
        }
    }

    private static BigDecimal decimal(String key) {
        if (key == null) {
            return null;
        }
        try {
            return new BigDecimal(key);
        } catch (NumberFormatException e) {
            throw InvalidCursorException.undecodable(
                    "That cursor is not a cursor this service issued.");
        }
    }

    private static Integer integer(String key) {
        if (key == null) {
            return null;
        }
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException e) {
            throw InvalidCursorException.undecodable(
                    "That cursor is not a cursor this service issued.");
        }
    }

    /** The key of the last row on this page, as the cursor stores it. */
    private static String sortKey(DiscoverySort sort, Row row) {
        ProjectCard card = row.card();
        return switch (sort) {
            case NEWEST -> card.launchedAt() == null ? null : card.launchedAt().toString();
            case ENDING_SOON -> card.deadline() == null ? null : card.deadline().toString();
            case MOST_FUNDED -> card.pledged().amount().toPlainString();
            case MOST_BACKED -> Integer.toString(card.backersCount());
            case POPULARITY, BEST_MATCH -> row.score().toPlainString();
            case RELEVANCE, NEAR_ME -> throw new IllegalStateException(
                    sort.wireValue() + " is refused before it reaches the query");
        };
    }

    /**
     * The instant this request scores and counts down from.
     *
     * <p>Taken from the cursor when there is one, so that every page of a scroll
     * agrees; truncated to {@link #POPULARITY_BUCKET} otherwise, so that two requests
     * inside one cache window produce the same bytes.
     */
    private Instant asOf(DiscoveryQuery query) {
        if (query.cursor() != null) {
            return query.cursor().asOf();
        }
        long seconds = POPULARITY_BUCKET.toSeconds();
        Instant now = clock.instant();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    }

    // ---------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------

    /**
     * A card, and the sort key that produced it.
     *
     * @param score null except under {@link DiscoverySort#POPULARITY} and
     *     {@link DiscoverySort#BEST_MATCH}, the two orders whose key is computed by
     *     the database and cannot be recovered from the card
     */
    private record Row(ProjectCard card, BigDecimal score) {
    }

    /** One line of the facet query, before it is sorted back into dimensions. */
    private record FacetRow(String dimension, String key, String extra, long count) {
    }

    private static Row toRow(ResultSet resultSet, Instant asOf) throws SQLException {
        String currency = resultSet.getString("currency");
        BigDecimal goalAmount = resultSet.getBigDecimal("goal_amount");
        BigDecimal pledgedAmount = resultSet.getBigDecimal("pledged_amount");
        Instant launchedAt = instantOf(resultSet, "launched_at");
        Instant deadline = instantOf(resultSet, "deadline");

        String coverUrl = resultSet.getString("cover_image_url");
        // The three cover columns are written together or not at all —
        // projects_cover_image_is_complete — so one check answers for all three.
        ProjectCard.CoverImage cover = coverUrl == null
                ? null
                : new ProjectCard.CoverImage(
                        coverUrl, resultSet.getInt("cover_image_width"), resultSet.getInt("cover_image_height"));

        ProjectCard card = new ProjectCard(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("slug"),
                resultSet.getString("creator_slug"),
                resultSet.getString("title"),
                new ProjectCard.Creator(
                        resultSet.getString("creator_name"),
                        resultSet.getString("creator_slug"),
                        resultSet.getString("creator_avatar_url")),
                cover,
                Money.orNull(goalAmount, currency),
                Money.of(pledgedAmount, currency),
                ProjectCard.completionPercent(pledgedAmount, goalAmount),
                resultSet.getInt("backers_count"),
                // The same instant the scores were computed against, so that a page
                // is internally consistent and so that the ETag over it is stable for
                // the length of the cache window.
                ProjectCard.daysLeft(deadline, asOf),
                DiscoveryStatus.badgeFor(resultSet.getString("state")).orElse(null),
                resultSet.getString("state"),
                launchedAt,
                deadline);
        return new Row(card, resultSet.getBigDecimal("sort_score"));
    }

    /**
     * A {@code timestamptz} as an instant.
     *
     * <p>Through {@code OffsetDateTime} rather than {@code getTimestamp}, which
     * reinterprets the value in the JVM's default zone and would make a deadline move
     * when the server's zone did.
     */
    private static Instant instantOf(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---------------------------------------------------------------------------
    // Facets
    // ---------------------------------------------------------------------------

    private static String facetSql(
            String publicStates,
            String matchesStatus,
            String matchesTaxonomy,
            String matchesTags,
            String matchesCompletion,
            String matchesGoal,
            String matchesRaised,
            MapSqlParameterSource params) {

        String statusValues = statusValues(params);
        String completionValues = bandValues(params, "completionBand", completionRows());
        String amountValues = bandValues(params, "amountBand", amountRows());

        // Every facet requires all the flags except its own. Written out per facet
        // rather than composed, so that a reader can see at the point of each count
        // which dimension was dropped.
        String exceptStatus = "b.m_taxonomy AND b.m_tags AND b.m_completion AND b.m_goal AND b.m_raised";
        String exceptTaxonomy = "b.m_status AND b.m_tags AND b.m_completion AND b.m_goal AND b.m_raised";
        String exceptTags = "b.m_status AND b.m_taxonomy AND b.m_completion AND b.m_goal AND b.m_raised";
        String exceptCompletion = "b.m_status AND b.m_taxonomy AND b.m_tags AND b.m_goal AND b.m_raised";
        String exceptGoal = "b.m_status AND b.m_taxonomy AND b.m_tags AND b.m_completion AND b.m_raised";
        String exceptRaised = "b.m_status AND b.m_taxonomy AND b.m_tags AND b.m_completion AND b.m_goal";

        return """
               WITH base AS MATERIALIZED (
                   SELECT p.id, p.state, p.category_id, p.subcategory_id,
                          p.goal_amount, p.pledged_amount,
                          (%s) AS m_status,
                          (%s) AS m_taxonomy,
                          (%s) AS m_tags,
                          (%s) AS m_completion,
                          (%s) AS m_goal,
                          (%s) AS m_raised
                     FROM projects p
                    WHERE %s
               ),
               status_map (value, state) AS (VALUES %s),
               completion_bands (value, lo, hi) AS (VALUES %s),
               amount_bands (value, lo, hi) AS (VALUES %s)
               SELECT 'status'::text AS dimension, sm.value AS key, NULL::text AS extra, count(b.id) AS n
                 FROM status_map sm
                 LEFT JOIN base b ON b.state = sm.state AND %s
                GROUP BY sm.value
               UNION ALL
               SELECT 'category'::text, c.slug, NULL::text, count(b.id)
                 FROM categories c
                 LEFT JOIN base b ON b.category_id = c.id AND %s
                GROUP BY c.slug
               UNION ALL
               SELECT 'subcategory'::text, c.slug, s.slug, count(b.id)
                 FROM subcategories s
                 JOIN categories c ON c.id = s.parent_id
                 LEFT JOIN base b ON b.subcategory_id = s.id AND %s
                GROUP BY c.slug, s.slug
               UNION ALL
               SELECT * FROM (
                   SELECT 'tag'::text AS dimension, t.slug, t.label, count(*) AS n
                     FROM base b
                     JOIN project_tags pt ON pt.project_id = b.id
                     JOIN tags t ON t.id = pt.tag_id
                    WHERE %s
                    GROUP BY t.slug, t.label
                    ORDER BY count(*) DESC, t.slug ASC
                    LIMIT %d
               ) AS ranked_tags
               UNION ALL
               SELECT 'completion'::text, cb.value, NULL::text, count(b.id)
                 FROM completion_bands cb
                 LEFT JOIN base b
                   ON b.goal_amount IS NOT NULL
                  AND b.pledged_amount * 100 >= b.goal_amount * cb.lo
                  AND (cb.hi IS NULL OR b.pledged_amount * 100 < b.goal_amount * cb.hi)
                  AND %s
                GROUP BY cb.value
               UNION ALL
               SELECT 'goal'::text, ab.value, NULL::text, count(b.id)
                 FROM amount_bands ab
                 LEFT JOIN base b
                   ON b.goal_amount >= ab.lo
                  AND (ab.hi IS NULL OR b.goal_amount < ab.hi)
                  AND %s
                GROUP BY ab.value
               UNION ALL
               SELECT 'raised'::text, ab.value, NULL::text, count(b.id)
                 FROM amount_bands ab
                 LEFT JOIN base b
                   ON b.pledged_amount >= ab.lo
                  AND (ab.hi IS NULL OR b.pledged_amount < ab.hi)
                  AND %s
                GROUP BY ab.value
               """
                .formatted(
                        matchesStatus,
                        matchesTaxonomy,
                        matchesTags,
                        matchesCompletion,
                        matchesGoal,
                        matchesRaised,
                        publicStates,
                        statusValues,
                        completionValues,
                        amountValues,
                        exceptStatus,
                        exceptTaxonomy,
                        exceptTaxonomy,
                        exceptTags,
                        FacetCounts.TAG_LIMIT,
                        exceptCompletion,
                        exceptGoal,
                        exceptRaised);
    }

    /**
     * A {@code VALUES} list of one row per (grouping, state) pair.
     *
     * <p>A pair rather than a grouping with a list, so that a state belonging to two
     * groupings — {@code LATE_PLEDGE} is both late-pledging and successful — is
     * counted in both without the query having to know that it is.
     *
     * <p>Bound rather than interpolated even though every value comes from an enum in
     * this package. A literal built by concatenation is a habit, and the next person
     * to reach for it will have something that is not from an enum.
     */
    private static String statusValues(MapSqlParameterSource params) {
        List<String> rendered = new ArrayList<>();
        int index = 0;
        for (DiscoveryStatus status : DiscoveryStatus.values()) {
            for (String state : status.states()) {
                String valueName = "statusValue" + index;
                String stateName = "statusState" + index;
                params.addValue(valueName, status.wireValue());
                params.addValue(stateName, state);
                // The first row of a VALUES list decides the column types, and a bound
                // parameter has none until it is told. Without the casts PostgreSQL
                // refuses the statement outright.
                rendered.add("(:" + valueName + "::text, :" + stateName + "::text)");
                index++;
            }
        }
        return String.join(", ", rendered);
    }

    private static List<List<Object>> completionRows() {
        List<List<Object>> rows = new ArrayList<>();
        for (CompletionBand band : CompletionBand.values()) {
            rows.add(row(band.wireValue(), band.lowerInclusive(), band.upperExclusive()));
        }
        return rows;
    }

    private static List<List<Object>> amountRows() {
        List<List<Object>> rows = new ArrayList<>();
        for (AmountBand band : AmountBand.values()) {
            rows.add(row(band.wireValue(), band.lowerInclusive(), band.upperExclusive()));
        }
        return rows;
    }

    /** A three-column band row; the upper bound is null on the open-ended band. */
    private static List<Object> row(String value, BigDecimal lower, BigDecimal upper) {
        List<Object> cells = new ArrayList<>();
        cells.add(value);
        cells.add(lower);
        cells.add(upper);
        return cells;
    }

    /** The same, for the band lists, whose second and third columns are numeric. */
    private static String bandValues(MapSqlParameterSource params, String prefix, List<List<Object>> rows) {
        List<String> rendered = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            String valueName = prefix + r + "_v";
            String loName = prefix + r + "_lo";
            String hiName = prefix + r + "_hi";
            params.addValue(valueName, row.get(0));
            params.addValue(loName, row.get(1));
            params.addValue(hiName, row.get(2));
            rendered.add("(:" + valueName + "::text, :" + loName + "::numeric, :" + hiName + "::numeric)");
        }
        return String.join(", ", rendered);
    }

    /**
     * The counts, with the taxonomy's names and every zero-count value put back.
     *
     * <p>A dimension with a fixed vocabulary answers for all of its values, whether or
     * not the query produced a row for each: a filter control that vanishes when its
     * count reaches zero is a control that moves under the reader's cursor.
     */
    private FacetCounts assemble(
            String locale,
            Map<String, Map<String, Long>> counts,
            Map<String, String> tagLabels,
            List<String> tagOrder) {

        Map<String, Long> statuses = counts.getOrDefault("status", Map.of());
        Map<String, Long> categories = counts.getOrDefault("category", Map.of());
        Map<String, Long> subcategories = counts.getOrDefault("subcategory", Map.of());
        Map<String, Long> tags = counts.getOrDefault("tag", Map.of());
        Map<String, Long> completion = counts.getOrDefault("completion", Map.of());
        Map<String, Long> goal = counts.getOrDefault("goal", Map.of());
        Map<String, Long> raised = counts.getOrDefault("raised", Map.of());

        List<FacetCounts.CategoryCount> categoryCounts = new ArrayList<>();
        for (Taxonomy.TaxonomyCategory category : taxonomy.all(locale)) {
            List<FacetCounts.NamedCount> children = new ArrayList<>();
            for (Taxonomy.TaxonomySubcategory subcategory : category.subcategories()) {
                children.add(new FacetCounts.NamedCount(
                        subcategory.slug(),
                        subcategory.name(),
                        subcategories.getOrDefault(category.slug() + "/" + subcategory.slug(), 0L)));
            }
            categoryCounts.add(new FacetCounts.CategoryCount(
                    category.slug(), category.name(), categories.getOrDefault(category.slug(), 0L), children));
        }

        List<FacetCounts.NamedCount> tagCounts = new ArrayList<>();
        for (String slug : tagOrder) {
            tagCounts.add(new FacetCounts.NamedCount(slug, tagLabels.get(slug), tags.getOrDefault(slug, 0L)));
        }

        return new FacetCounts(
                fixedVocabulary(DiscoveryStatus.wireValues(), statuses),
                categoryCounts,
                tagCounts,
                fixedVocabulary(CompletionBand.wireValues(), completion),
                fixedVocabulary(AmountBand.wireValues(), goal),
                fixedVocabulary(AmountBand.wireValues(), raised));
    }

    private static List<FacetCounts.ValueCount> fixedVocabulary(List<String> values, Map<String, Long> counts) {
        List<FacetCounts.ValueCount> result = new ArrayList<>();
        for (String value : values) {
            result.add(new FacetCounts.ValueCount(value, counts.getOrDefault(value, 0L)));
        }
        return result;
    }

    // ---------------------------------------------------------------------------

    private static void add(List<String> where, String clause) {
        if (!"true".equals(clause)) {
            where.add(clause);
        }
    }

    private static String and(String left, String right) {
        if ("true".equals(left)) {
            return right;
        }
        if ("true".equals(right)) {
            return left;
        }
        return "(" + left + " AND " + right + ")";
    }
}
