package az.ideanest.project.infrastructure;

import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a campaign is called, published so that other modules need not read {@code projects}.
 *
 * <p>The project module's half of #249. {@code ProjectSummaries} is the question and this is
 * the only class that answers it, for the reason {@code PledgeProjectAudiences} gives about
 * its own table: {@code projects} is this module's and {@code ModuleBoundaryTests} forbids
 * anybody else from naming it. Before this existed, every notification about a campaign
 * called it "this campaign", because there was nowhere for a title to come from.
 *
 * <h2>No state filter, deliberately</h2>
 *
 * <p>{@code PublicProjectPages} beside this one filters on §6.1's published states and is
 * right to — it serves the public page. This one serves messages addressed to people who are
 * already party to the campaign, so filtering would remove the title from precisely the
 * notifications about a campaign that has stopped being public: suspended, cancelled,
 * unsuccessful. {@code ProjectSummaries} makes the argument in full.
 *
 * <p>That is also why this reads nothing but four columns. It is not a projection of a
 * campaign and must not become one: a caller that could read the goal or the state from here
 * would be reading this module's business through a hole meant for a name.
 *
 * <h2>{@code LEFT JOIN}, and what it is defending</h2>
 *
 * <p>{@code projects.creator_id} references {@code users} and {@code users.slug} is
 * {@code NOT NULL}, so the creator's half of the public path is there on every row a working
 * database holds. An inner join would nonetheless mean that a campaign whose creator row
 * could not be found had <em>no title either</em> — one broken invariant turning a message
 * that could have named the campaign into one that could not. The outer join keeps the
 * title; {@code ProjectSummary.hasPublicPath} is what stops the missing slug becoming a
 * link.
 *
 * <h2>SQL rather than the repository</h2>
 *
 * <p>{@code ProjectRepository} maps an entity with fifty columns, a story and an outcome.
 * This is called once per event translated, inside the outbox dispatch transaction, and
 * needs a name — loading the aggregate to read one field would put the campaign's whole
 * story through Hibernate to write a title into a JSON document.
 *
 * <p>{@code @Component} rather than {@code @Repository}, following
 * {@code PledgeProjectAudiences}: exception translation buys nothing here and would turn the
 * argument check below into a {@code DataAccessException} blaming the database for something
 * it was never shown.
 */
@Component
public class ProjectSummaryLookup implements ProjectSummaries {

    private static final String COLUMNS = "p.id, p.title, p.slug, u.slug AS creator_slug, p.creator_id";

    private static final String SUMMARY_OF = "SELECT " + COLUMNS
            + " FROM projects p LEFT JOIN users u ON u.id = p.creator_id WHERE p.id = :projectId";

    /**
     * The batch form.
     *
     * <p>An expanded {@code IN} list rather than {@code = ANY} over a bound array. The array
     * form would be one prepared statement whatever the page size, which is the better shape —
     * but binding a {@code UUID[]} depends on the driver rather than on the standard, and the
     * caller's page is bounded at a few dozen identifiers. A statement per distinct page
     * length is a cost the plan cache absorbs; a driver-specific bind is a cost somebody
     * discovers during an upgrade.
     */
    private static final String SUMMARIES_OF = "SELECT " + COLUMNS
            + " FROM projects p LEFT JOIN users u ON u.id = p.creator_id WHERE p.id IN (:projectIds)";

    private static final RowMapper<ProjectSummary> SUMMARY = (row, index) -> new ProjectSummary(
            row.getObject("id", UUID.class),
            row.getString("title"),
            row.getString("slug"),
            row.getString("creator_slug"),
            row.getObject("creator_id", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectSummaryLookup(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectSummary> summaryOf(UUID projectId) {
        if (projectId == null) {
            // Empty rather than a failure, for the reason the interface gives: the caller is
            // translating an event shared with other modules and must not be able to fail
            // their writes over a field it cannot use.
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(
                    jdbc.queryForObject(SUMMARY_OF, new MapSqlParameterSource("projectId", projectId), SUMMARY));
        } catch (EmptyResultDataAccessException noSuchCampaign) {
            // `projects.id` is the primary key, so there is no "more than one" case to
            // distinguish. A campaign that does not exist is an ordinary answer here --
            // an event may outlive its subject.
            return Optional.empty();
        }
    }

    @Override
    public List<ProjectSummary> summariesOf(Collection<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            // No statement at all rather than one that can match nothing. Null is an empty
            // answer here for `summaryOf`'s reason, and an empty collection is a caller with
            // an empty page, which is ordinary.
            return List.of();
        }

        // Deduplicated before the array is built, so that a caller holding the same campaign
        // twice cannot make the answer carry it twice -- the interface promises it will not.
        Set<UUID> distinct = Set.copyOf(projectIds);
        return List.copyOf(jdbc.query(SUMMARIES_OF, new MapSqlParameterSource("projectIds", distinct), SUMMARY));
    }
}
