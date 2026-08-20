package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.application.BackerFilter;
import az.ideanest.pledge.application.BackerSegment;
import az.ideanest.pledge.domain.PledgeState;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/**
 * V31's {@code backer_segments}, read and written.
 *
 * <h2>Why this is not a JPA entity</h2>
 *
 * <p>Three of the five columns that matter are PostgreSQL arrays, and Hibernate's array
 * support is the kind that works until somebody writes an empty one. The mapping here is
 * explicit in both directions — {@code null} for "any", never a zero-length array, which
 * is what V31's checks refuse — and the conversion lives beside the constraint it has to
 * satisfy rather than in an annotation two layers away.
 *
 * <p>{@code NamedParameterJdbcTemplate} for the same reason as the report next door, and
 * with the same consequence: this repository does not participate in the persistence
 * context, so nothing here is dirty-checked and every write is the statement it looks
 * like.
 *
 * <h2>Uniqueness is the database's answer, not this class's</h2>
 *
 * <p>{@link #save} does not check whether the name is taken. It inserts, and lets
 * {@code backer_segments_project_name_key} refuse; the caller turns that refusal into a
 * 409. A read-then-write here would lose the race between two tabs and produce two
 * segments called "Germany" on one campaign — which is precisely what the index exists to
 * prevent and precisely what a service-level check cannot.
 */
@Repository
public class BackerSegmentRepository {

    private static final String COLUMNS =
            "id, project_id, name, states, reward_tier_ids, countries, term, created_by, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public BackerSegmentRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** Every segment on a campaign, newest first. */
    public List<BackerSegment> of(UUID projectId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM backer_segments WHERE project_id = :projectId ORDER BY created_at DESC",
                new MapSqlParameterSource("projectId", projectId),
                SEGMENT);
    }

    /**
     * One segment, if it is on this campaign.
     *
     * <p>Scoped to the campaign rather than looked up by identifier alone, so that a
     * segment belonging to somebody else's campaign is indistinguishable from one that does
     * not exist. The caller has been authorised on the campaign in the path and on nothing
     * else.
     */
    public Optional<BackerSegment> find(UUID projectId, UUID segmentId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM backer_segments WHERE id = :id AND project_id = :projectId",
                        new MapSqlParameterSource("id", segmentId).addValue("projectId", projectId),
                        SEGMENT)
                .stream()
                .findFirst();
    }

    /** How many this campaign already has, for the limit the service enforces. */
    public int countOf(UUID projectId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM backer_segments WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId),
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Stores a new segment.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the campaign already has
     *     one by that name, folded. The caller translates it
     */
    public BackerSegment save(BackerSegment segment) {
        jdbc.update(
                """
                INSERT INTO backer_segments (id, project_id, name, states, reward_tier_ids, countries, term,
                                             created_by, created_at, updated_at)
                VALUES (:id, :projectId, :name, :states, :rewardTiers, :countries, :term,
                        :createdBy, :createdAt, :updatedAt)
                """,
                parametersOf(segment));
        return segment;
    }

    /**
     * Replaces a segment's name and filter.
     *
     * <p>The whole filter, never a part of it. A partial update would need a way to say
     * "clear the country filter" that is distinguishable from "leave it alone", and
     * {@code Patched} exists elsewhere in this codebase for exactly that problem — but a
     * segment is four short axes a creator re-picks in one interaction, so the simpler
     * contract is the honest one.
     *
     * @return whether a row was there to replace
     */
    public boolean replace(BackerSegment segment) {
        return jdbc.update(
                        """
                        UPDATE backer_segments
                           SET name = :name, states = :states, reward_tier_ids = :rewardTiers,
                               countries = :countries, term = :term, updated_at = :updatedAt
                         WHERE id = :id AND project_id = :projectId
                        """,
                        parametersOf(segment))
                == 1;
    }

    /** @return whether a row was there to delete */
    public boolean delete(UUID projectId, UUID segmentId) {
        return jdbc.update(
                        "DELETE FROM backer_segments WHERE id = :id AND project_id = :projectId",
                        new MapSqlParameterSource("id", segmentId).addValue("projectId", projectId))
                == 1;
    }

    /**
     * The filter as three arrays and a string.
     *
     * <p><strong>Empty becomes null.</strong> {@link BackerFilter} uses an empty collection
     * for "this axis does not narrow anything" and V31 stores that as {@code NULL},
     * refusing a zero-length array so that the same fact has one representation in the
     * database. This method is the one place the two vocabularies meet.
     *
     * <p>Each array is wrapped in a {@link SqlArrayValue}, which is not decoration: a bare
     * {@code Object[]} handed to a named parameter is <em>expanded into a comma-separated
     * list of placeholders</em> by Spring, which is what makes {@code IN (:ids)} work and
     * what would silently turn this insert into a syntax error. The wrapper says "one
     * value, of array type" and builds it from the connection.
     */
    private static MapSqlParameterSource parametersOf(BackerSegment segment) {
        BackerFilter filter = segment.filter();

        return new MapSqlParameterSource("id", segment.id())
                .addValue("projectId", segment.projectId())
                .addValue("name", segment.name())
                .addValue(
                        "states",
                        array("text", filter.states().stream().map(PledgeState::name).sorted().toArray()))
                .addValue("rewardTiers", array("uuid", filter.rewardTiers().toArray()))
                .addValue("countries", array("text", filter.countries().stream().sorted().toArray()))
                .addValue("term", filter.term())
                .addValue("createdBy", segment.createdBy())
                .addValue("createdAt", at(segment.createdAt()))
                .addValue("updatedAt", at(segment.updatedAt()));
    }

    /** An array parameter, or null when the axis does not narrow anything. */
    private static SqlArrayValue array(String type, Object[] elements) {
        return elements.length == 0 ? null : new SqlArrayValue(type, elements);
    }

    /**
     * {@link OffsetDateTime} at UTC rather than {@link Instant}, which the PostgreSQL
     * driver does not bind for a {@code timestamptz}. {@code DailyRollupRepository} makes
     * the same conversion for the same reason.
     */
    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final RowMapper<BackerSegment> SEGMENT = (ResultSet rs, int row) -> segmentOf(rs);

    private static BackerSegment segmentOf(ResultSet rs) throws SQLException {
        return new BackerSegment(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("name"),
                new BackerFilter(
                        states(rs.getArray("states")),
                        uuids(rs.getArray("reward_tier_ids")),
                        strings(rs.getArray("countries")),
                        rs.getString("term")),
                rs.getObject("created_by", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /**
     * A stored state array, back to the enum.
     *
     * <p>{@code valueOf} rather than a lenient lookup: V31's check constraint already
     * refuses anything outside the five, so a value here that does not parse means the
     * constraint was bypassed, and failing loudly is the only useful response to that.
     */
    private static Set<PledgeState> states(Array stored) throws SQLException {
        return strings(stored).stream()
                .map(PledgeState::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> strings(Array stored) throws SQLException {
        if (stored == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList((String[]) stored.getArray()));
    }

    private static Set<UUID> uuids(Array stored) throws SQLException {
        if (stored == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList((UUID[]) stored.getArray()));
    }
}
