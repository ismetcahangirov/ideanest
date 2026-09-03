package az.ideanest.project.infrastructure;

import az.ideanest.shared.Slugs;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The console's campaign directory as SQL — issues #387 and #404.
 *
 * <h2>Why this left {@code ProjectRepository}</h2>
 *
 * <p>The directory used to be four {@code @Query} methods there: one per combination of "a
 * state or every state" and "the first page or a later one". That worked because there were
 * two filters. #404 adds two more — a search term and a creator — and four combinations
 * become sixteen, which is sixteen statements describing one query.
 *
 * <p>Spring Data cannot express a predicate that is sometimes absent without a nullable
 * parameter, and {@code ProjectRepository} spells out its variants precisely to avoid one:
 * a {@code :state IS NULL OR …} predicate in a <em>native</em> query hands the driver a
 * parameter whose type it has to guess. That reasoning is unchanged and is why this is a
 * different class rather than a fifth method — the SQL is assembled here, so a predicate
 * that is absent is absent from the statement rather than present and disabled, and every
 * bound parameter has exactly one type.
 *
 * <p>{@code NamedParameterJdbcTemplate} for the reason {@link BackerListRepository} gives
 * about its own filter: §11 names jOOQ for the day the queries justify it, and one assembled
 * statement does not.
 *
 * <h2>What the search matches, and what it costs</h2>
 *
 * <p>#404 asks for title, creator and identifier, because those are the three things a
 * complaint about a campaign arrives holding. So:
 *
 * <ul>
 *   <li><strong>A term that parses as a UUID is an identifier</strong>, matched exactly
 *       against the campaign and against its creator. Nothing else — a UUID is not prose, and
 *       a contains-match on one would find a campaign whose title happens to quote it.
 *   <li><strong>Anything else is a contains-match</strong> on the campaign's title and path
 *       and on the creator's name and path. Folded by §11.3 through {@code ideanest_fold},
 *       which is what makes "kohne" find "Köhnə" — and which is the expression V13's title
 *       index and V63's two account indexes are built on, so the match is index-backed rather
 *       than merely correct.
 * </ul>
 *
 * <p><strong>The honest part: an {@code OR} across a join is not one index scan.</strong>
 * PostgreSQL can combine the two {@code projects} predicates into a bitmap, and it cannot
 * fold the two {@code users} predicates into the same one — so a search by creator name is a
 * join whose driving side the planner chooses, and at some size that becomes the scan this
 * module has spent three files avoiding. It is affordable at the size this screen is for: an
 * operator searching a catalogue of campaigns, not a request path. The day it is not, the
 * answer is a materialised search column on {@code projects} maintained by a trigger — V13
 * already built exactly that for public search — and not another index.
 *
 * <h2>{@code users} is joined, and only for the search</h2>
 *
 * <p>The join is added when there is a term and omitted otherwise, which is the shape
 * {@link BackerListRepository#count} uses: an unfiltered page selects no column from
 * {@code users}, so joining unconditionally would be one lookup per row for nothing.
 *
 * <p>It is a {@code LEFT} join, so that §17.4's anonymisation cannot make a campaign vanish
 * from the directory: the account goes and the campaign stays, which is the whole point of
 * that rule, and an inner join would quietly enforce the opposite.
 *
 * <p>Joining another module's table at all is the same licence {@link ProfileCampaignRows}
 * and {@link PublicProjectPages} already take in this package — {@code projects.creator_id}
 * is this module's own foreign key, and an account's display name has no owning module that
 * publishes it in SQL. The <em>name</em> is still not read here: it comes back through
 * {@code UserAccounts} in the application layer, one lookup per page, exactly as it did
 * before. What crosses in SQL is a predicate, not a projection.
 */
@Repository
public class CampaignDirectoryRows {

    /**
     * The columns, which are the ones {@code CampaignDirectoryRow} names.
     *
     * <p>Deliberately not {@code p.*}: {@code projects.story} is a jsonb document, and a
     * directory of twenty-five campaigns that selected it would move several hundred
     * kilobytes to render a list of titles. That is the reason this read was a projection
     * before it was a statement.
     */
    private static final String COLUMNS =
            """
            SELECT p.id AS project_id, p.title AS title, p.slug AS slug,
                   p.state AS state, p.created_at AS created_at,
                   p.launched_at AS launched_at, p.deadline AS deadline,
                   p.goal_amount AS goal_amount, p.currency AS currency,
                   p.pledged_amount AS pledged_amount, p.backers_count AS backers_count,
                   p.creator_id AS creator_id
              FROM projects p
            """;

    /**
     * Ordered by {@code (created_at, id)} rather than by the key alone.
     *
     * <p>The primary key is a UUID v7 and is therefore time-ordered for everything this
     * platform has written, but not for anything seeded or migrated in with a key from
     * somewhere else — and #404 is the issue that found out what that costs on the audit
     * trail, which displayed one column and ordered by the other. Ordering on the column that
     * means what it says costs an index and cannot be wrong.
     */
    private static final String ORDER = " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit";

    private final NamedParameterJdbcTemplate jdbc;

    public CampaignDirectoryRows(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * One page of the directory, newest first.
     *
     * @param state the state to narrow to, or null for every campaign
     * @param creatorId one person's campaigns, or null for everybody's. What the console's
     *     account detail screen is built on — #404 asks that a moderator can see what
     *     somebody has created before suspending them
     * @param term a search over the title, the two paths, the creator's name, or an
     *     identifier. Null or blank for no search
     * @param after the last campaign of the previous page, or null for the first. A campaign
     *     that has since been deleted yields an empty page rather than the first one again:
     *     silently restarting a list somebody is halfway through is how a moderator reads the
     *     same twenty campaigns twice
     * @param limit already clamped by the caller, which is where a request's shape is decided
     */
    public List<CampaignDirectoryRow> page(String state, UUID creatorId, String term, UUID after, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("limit", limit);
        StringBuilder sql = new StringBuilder(COLUMNS);

        UUID identifier = identifierIn(term);
        String pattern = identifier == null ? patternOf(term) : null;

        if (pattern != null) {
            sql.append(" LEFT JOIN users u ON u.id = p.creator_id");
        }

        sql.append(" WHERE TRUE");

        if (state != null) {
            sql.append(" AND p.state = :state");
            parameters.addValue("state", state);
        }
        if (creatorId != null) {
            sql.append(" AND p.creator_id = :creatorId");
            parameters.addValue("creatorId", creatorId);
        }
        if (identifier != null) {
            sql.append(" AND (p.id = :identifier OR p.creator_id = :identifier)");
            parameters.addValue("identifier", identifier);
        }
        if (pattern != null) {
            sql.append(
                    """
                     AND (ideanest_fold(p.title) LIKE :pattern ESCAPE '!'
                          OR p.slug LIKE :pattern ESCAPE '!'
                          OR ideanest_fold(u.name) LIKE :pattern ESCAPE '!'
                          OR ideanest_fold(u.slug) LIKE :pattern ESCAPE '!')
                    """);
            parameters.addValue("pattern", pattern);
        }
        if (after != null) {
            // A row comparison against the named campaign's own key, which is what makes one
            // identifier enough for a two-column order. Looked up in the same statement, so
            // the cursor stays a bare UUID rather than an encoded pair a client would be
            // tempted to construct.
            sql.append(
                    """
                     AND (p.created_at, p.id) <
                         (SELECT c.created_at, c.id FROM projects c WHERE c.id = :after)
                    """);
            parameters.addValue("after", after);
        }

        return jdbc.query(sql.append(ORDER).toString(), parameters, CAMPAIGN);
    }

    /**
     * The term as an identifier, or null when it is not one.
     *
     * <p>Tried before the text match rather than as well as it. Somebody pasting a UUID into
     * the search box is holding a campaign identifier from a report or a support ticket and
     * means that campaign — and a contains-match on thirty-six characters of hexadecimal is
     * a scan that finds nothing.
     */
    private static UUID identifierIn(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(term.trim());
        } catch (IllegalArgumentException notAnIdentifier) {
            return null;
        }
    }

    /**
     * The term as a folded {@code LIKE} pattern, wrapped in wildcards at both ends.
     *
     * <p>Folded by {@link Slugs#fold}, which is the Java half of {@code ideanest_fold} —
     * V13's comment names it as the mirror. Folding here and in the predicate is what makes
     * the comparison index-backed on both sides: an expression index only serves a query that
     * repeats the expression, and a term folded in only one of the two places would match
     * nothing rather than match slowly.
     *
     * <p>The wildcards a caller typed are escaped, so a search for {@code 100%} is a search
     * for {@code 100%} rather than a match on everything. {@code UserDirectory.patternOf} does
     * the same for the account directory, with {@code lower} in place of the fold.
     */
    private static String patternOf(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String escaped = Slugs.fold(term.trim())
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return escaped.isEmpty() ? null : "%" + escaped + "%";
    }

    /**
     * One row.
     *
     * <p>{@code OffsetDateTime} out of the driver and {@link Instant} into the record, which
     * is what every other hand-written mapper in this codebase does: {@code timestamptz} has
     * an offset on the wire and the platform's own vocabulary for a moment is UTC.
     */
    private static final RowMapper<CampaignDirectoryRow> CAMPAIGN = (ResultSet row, int index) ->
            new CampaignDirectoryRow(
                    row.getObject("project_id", UUID.class),
                    row.getString("title"),
                    row.getString("slug"),
                    row.getString("state"),
                    instantOf(row, "created_at"),
                    instantOf(row, "launched_at"),
                    instantOf(row, "deadline"),
                    row.getBigDecimal("goal_amount"),
                    row.getString("currency"),
                    amountOf(row.getBigDecimal("pledged_amount")),
                    row.getInt("backers_count"),
                    row.getObject("creator_id", UUID.class));

    private static Instant instantOf(ResultSet row, String column) throws SQLException {
        OffsetDateTime moment = row.getObject(column, OffsetDateTime.class);
        return moment == null ? null : moment.toInstant();
    }

    /**
     * What a campaign has raised, which is zero rather than nothing when it has raised none.
     *
     * <p>V6 makes the column {@code NOT NULL DEFAULT 0}, so this is a belt-and-braces read of
     * a value that cannot be null — and it is the one figure on the row where a null would be
     * rendered as "unknown" beside a goal, which reads as a broken screen rather than a new
     * campaign.
     */
    private static BigDecimal amountOf(BigDecimal pledged) {
        return pledged == null ? BigDecimal.ZERO : pledged;
    }
}
