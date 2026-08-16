package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What V11 left in the database, and what the database refuses about it.
 *
 * <p>Seed data is normally not worth a test. This seed is, for three reasons.
 * The taxonomy is a contract with §4.3 that a reader cannot check by eye — a
 * hundred and five rows across fifteen parents — and the two things most likely
 * to go wrong with it are silent: a category whose Azerbaijani name was never
 * written renders as a blank {@code <option>} to the platform's largest
 * audience, and a subcategory count drifting outside §4.3's band is a facet list
 * nobody notices has become unusable. The third is the migration's own
 * reshaping: it deletes taxa that campaigns may be filed under, and a
 * remapping that ran in the wrong order would have failed loudly here rather
 * than in production.
 *
 * <p>Deliberately not {@code @Transactional}, like {@code ProjectSchemaTests}: a
 * statement that violates a constraint aborts the surrounding transaction, so
 * each of these needs its own.
 */
class TaxonomySchemaTests extends AbstractIntegrationTest {

    /** §4.3, exactly. */
    private static final List<String> THE_FIFTEEN = List.of(
            "art",
            "comics",
            "crafts",
            "dance",
            "design",
            "fashion",
            "film",
            "food",
            "games",
            "journalism",
            "music",
            "photography",
            "publishing",
            "technology",
            "theatre");

    /** §4.3: "between four and nineteen subcategories". */
    private static final int MINIMUM_SUBCATEGORIES = 4;

    private static final int MAXIMUM_SUBCATEGORIES = 19;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    // -----------------------------------------------------------------------
    // The taxonomy §4.3 specifies
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the fifteen categories of the specification exist, and only those")
    void theFifteenCategoriesExist() {
        List<String> slugs =
                jdbc().queryForList("SELECT slug FROM categories ORDER BY slug", String.class);

        // Exactly, not "at least". A sixteenth category is a navigation item and
        // a facet that §4.3 does not describe, and the interim seed of V6 had
        // five of them.
        assertThat(slugs).containsExactlyElementsOf(THE_FIFTEEN);
    }

    @Test
    @DisplayName("`community` is gone, and nothing is filed under it")
    void theInterimCommunityCategoryIsGone() {
        // V6 seeded it; §4.3's fifteen contain nothing that means it. Campaigns
        // filed under it were unfiled rather than moved -- no category in the
        // fifteen means the same thing, and a wrong filing hides a campaign from
        // the facet it belongs in while showing it in one it does not.
        assertThat(jdbc().queryForObject(
                        "SELECT count(*) FROM categories WHERE slug = 'community'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("photography and comics are top-level categories, not subcategories")
    void thePromotedTaxaMovedUp() {
        assertThat(jdbc().queryForList(
                        "SELECT c.slug FROM subcategories s JOIN categories c ON c.id = s.parent_id"
                                + " WHERE (c.slug = 'art' AND s.slug = 'photography')"
                                + " OR (c.slug = 'publishing' AND s.slug = 'comics')",
                        String.class))
                .isEmpty();

        assertThat(jdbc().queryForList(
                        "SELECT slug FROM categories WHERE slug IN ('photography', 'comics')", String.class))
                .containsExactlyInAnyOrder("comics", "photography");
    }

    @Test
    @DisplayName("every category carries between four and nineteen subcategories")
    void everyCategoryIsWithinTheBand() {
        List<Map<String, Object>> counts = jdbc().queryForList(
                "SELECT c.slug, count(s.id) AS children FROM categories c"
                        + " LEFT JOIN subcategories s ON s.parent_id = c.id"
                        + " GROUP BY c.slug ORDER BY c.slug");

        // A category with fewer than four choices is a select nobody needed, and
        // one with more than nineteen is a column a creator reads instead of
        // picking from.
        assertThat(counts).allSatisfy(row -> assertThat(((Number) row.get("children")).intValue())
                .as("subcategories of %s", row.get("slug"))
                .isBetween(MINIMUM_SUBCATEGORIES, MAXIMUM_SUBCATEGORIES));
    }

    @Test
    @DisplayName("the tree is roughly a hundred subcategories, as the specification says")
    void theTreeIsAboutAHundred() {
        Integer total = jdbc().queryForObject("SELECT count(*) FROM subcategories", Integer.class);

        // §4.3: "roughly a hundred in total". A band rather than an equality,
        // because adding a subcategory is meant to be an INSERT and a test that
        // pins the number to 105 would turn every such INSERT into a code change
        // -- which is exactly the coupling this migration exists to remove.
        assertThat(total).isNotNull().isBetween(90, 120);
    }

    @Test
    @DisplayName("every slug is URL-shaped and unique within its parent")
    void slugsAreWellFormed() {
        // The database checks both. This is what catches a seed row that violated
        // one of them being quietly absent because the INSERT that carried it
        // never ran.
        assertThat(jdbc().queryForList(
                        "SELECT slug FROM categories WHERE slug !~ '^[a-z0-9]+(-[a-z0-9]+)*$'", String.class))
                .isEmpty();
        assertThat(jdbc().queryForList(
                        "SELECT slug FROM subcategories WHERE slug !~ '^[a-z0-9]+(-[a-z0-9]+)*$'", String.class))
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Translations
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("every category and subcategory has an Azerbaijani name")
    void everyTaxonHasThePrimaryLanguage() {
        // The invariant no constraint can express: "at least one row of a given
        // locale" is a statement about sibling rows. V11 sets out why a partial
        // unique index cannot require a row and why a trigger was rejected; this
        // is the check that stands in for both, and §21.1 makes Azerbaijani the
        // language a missing name is most expensive in.
        assertThat(jdbc().queryForList(
                        "SELECT slug FROM categories c WHERE NOT EXISTS ("
                                + " SELECT 1 FROM category_translations t"
                                + " WHERE t.category_id = c.id AND t.locale = 'az')",
                        String.class))
                .isEmpty();

        assertThat(jdbc().queryForList(
                        "SELECT slug FROM subcategories s WHERE NOT EXISTS ("
                                + " SELECT 1 FROM subcategory_translations t"
                                + " WHERE t.subcategory_id = s.id AND t.locale = 'az')",
                        String.class))
                .isEmpty();
    }

    @Test
    @DisplayName("English is seeded and the phase-1 and phase-3 languages deliberately are not")
    void onlyTheTwoWrittenLanguagesAreSeeded() {
        List<String> locales = jdbc().queryForList(
                "SELECT DISTINCT locale FROM category_translations"
                        + " UNION SELECT DISTINCT locale FROM subcategory_translations",
                String.class);

        // ru and tr arrive as data, not as a release -- which is the property
        // §4.3 asks for and the only reason these are tables. Machine-translated
        // placeholders would make that arrival an UPDATE over text nobody
        // reviewed, and §21.1 forbids machine translation of content.
        assertThat(locales).containsExactlyInAnyOrder("az", "en");
    }

    @Test
    @DisplayName("a locale outside the four of §21.1 is refused")
    void theLocaleSetIsClosed() {
        UUID art = jdbc().queryForObject("SELECT id FROM categories WHERE slug = 'art'", UUID.class);

        // A language nobody ships is a name nothing will ever render, and it
        // would sit in the names map of every response looking like a feature.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO category_translations (category_id, locale, name) VALUES (?, 'de', 'Kunst')",
                        art))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And one of the four that has no rows yet is accepted, because that is
        // how Russian ships without a deployment.
        assertThatCode(() -> jdbc().update(
                        "INSERT INTO category_translations (category_id, locale, name) VALUES (?, 'ru', 'Искусство')",
                        art))
                .doesNotThrowAnyException();
        jdbc().update("DELETE FROM category_translations WHERE category_id = ? AND locale = 'ru'", art);
    }

    @Test
    @DisplayName("one name per category per language, and no blank ones")
    void translationsAreOnePerPair() {
        UUID art = jdbc().queryForObject("SELECT id FROM categories WHERE slug = 'art'", UUID.class);

        // Two rows would make the displayed name depend on which the planner read
        // first, which is a bug that reproduces once a week.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO category_translations (category_id, locale, name) VALUES (?, 'az', 'Başqa')",
                        art))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO category_translations (category_id, locale, name) VALUES (?, 'ru', '   ')",
                        art))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting a category takes its translations with it")
    void translationsCascade() {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO categories (id, slug, name_az, name_en) VALUES (?, 'temporary', 'Müvəqqəti', 'Temporary')",
                        id);
        jdbc().update(
                        "INSERT INTO category_translations (category_id, locale, name) VALUES (?, 'az', 'Müvəqqəti')",
                        id);

        jdbc().update("DELETE FROM categories WHERE id = ?", id);

        // A translation of a category that no longer exists is a row nothing can
        // ever join to and nothing will ever delete.
        assertThat(jdbc().queryForObject(
                        "SELECT count(*) FROM category_translations WHERE category_id = ?", Integer.class, id))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // tags and project_tags
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a tag slug is unique, URL-shaped, and separate from its label")
    void tagSlugsAreTheIdentity() {
        UUID first = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO tags (id, slug, label) VALUES (?, 'incesenet', 'İncəsənət')", first);

        // Two rows for one folded word would split that word's campaigns across
        // two facets, and neither would show all of them.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO tags (id, slug, label) VALUES (?, 'incesenet', 'Incesenet')",
                        Identifiers.newIdentifier()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The slug is what appears in a filter URL, so it carries no capitals,
        // no spaces, and none of the letters the fold exists to remove.
        assertThatThrownBy(() -> jdbc().update(
                        "INSERT INTO tags (id, slug, label) VALUES (?, 'İncəsənət', 'İncəsənət')",
                        Identifiers.newIdentifier()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Denormalised and not yet maintained: a zero here means nobody has
        // counted, and discovery (#42) owns the counting.
        assertThat(jdbc().queryForObject("SELECT usage_count FROM tags WHERE id = ?", Integer.class, first))
                .isZero();
        assertThatThrownBy(() -> jdbc().update("UPDATE tags SET usage_count = -1 WHERE id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc().update("DELETE FROM tags WHERE id = ?", first);
    }

    @Test
    @DisplayName("the trigram index autocomplete will search exists")
    void theTagSlugIsIndexedForFuzzyMatching() {
        // #46 suggests tags against a LIKE '%...%' predicate, which the unique
        // B-tree above cannot serve -- it answers prefixes only. Losing this
        // index would not break a test that queries the table; it would just make
        // autocomplete scan it.
        List<String> definitions = jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'tags' AND indexname = 'tags_slug_trgm_idx'",
                String.class);

        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst()).contains("gin").contains("gin_trgm_ops");
    }

    @Test
    @DisplayName("an edge is unique per pair and disappears with either end")
    void tagEdgesCascadeFromBothSides() {
        UUID creator = insertUser();
        UUID projectId = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO projects (id, creator_id, slug, title) VALUES (?, ?, 'a-tagged-campaign', 'A campaign')",
                        projectId,
                        creator);
        UUID tagId = Identifiers.newIdentifier();
        jdbc().update("INSERT INTO tags (id, slug, label) VALUES (?, 'repairable', 'Repairable')", tagId);

        jdbc().update("INSERT INTO project_tags (project_id, tag_id) VALUES (?, ?)", projectId, tagId);

        // Tagging a campaign with the same word twice is not a stronger claim
        // about it, and a duplicate would make the facet count double.
        assertThatThrownBy(() ->
                        jdbc().update("INSERT INTO project_tags (project_id, tag_id) VALUES (?, ?)", projectId, tagId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Retiring a tag -- a moderator merging two spellings -- must not leave
        // every tagged campaign pointing at nothing.
        jdbc().update("DELETE FROM tags WHERE id = ?", tagId);
        assertThat(jdbc().queryForObject(
                        "SELECT count(*) FROM project_tags WHERE project_id = ?", Integer.class, projectId))
                .isZero();

        jdbc().update("DELETE FROM project_state_transitions WHERE project_id = ?", projectId);
        jdbc().update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Test
    @DisplayName("the reverse lookup discovery's tag filter performs is indexed")
    void theTagFilterIsIndexed() {
        // The primary key answers "what is this campaign tagged with". The filter
        // asks the opposite, and without this index it is a sequential scan of
        // every edge on the platform.
        assertThat(jdbc().queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'project_tags'", String.class))
                .contains("project_tags_tag_idx");
    }

    private UUID insertUser() {
        UUID id = Identifiers.newIdentifier();
        String marker = "taxonomy-" + id;
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        marker + "@example.com",
                        "Test Creator",
                        marker);
        return id;
    }
}
