package az.ideanest.discovery.infrastructure;

import az.ideanest.discovery.application.RankingWeight;
import az.ideanest.discovery.domain.RankingTerm;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code ranking_weights} and its audit trail, over raw JDBC.
 *
 * <p>Raw SQL rather than JPA for the reason the rest of this module gives: nine rows
 * with no associations projected into a record is not work an entity manager makes
 * easier, and this table is read on the hot path of a feed that §20 budgets at a
 * thousand requests a second. Nothing here is cached — the cache belongs to
 * {@code RankingWeightStore}, which is the one place that decides how stale a weight may
 * be.
 */
@Repository
public class RankingWeightRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RankingWeightRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every term, in §11.2's order.
     *
     * <p>Ordered in Java by {@code RankingWeights.of} rather than by SQL: the order is
     * the enum's declaration order, which is §11.2's, and an {@code ORDER BY term} would
     * be alphabetical — putting the spam penalty third and the text term last, which is
     * nobody's reading of the formula.
     *
     * <p>A row whose {@code term} is not one this build knows is skipped rather than
     * failing the read. It cannot happen while the CHECK constraint and the enum agree;
     * what it protects is the minutes of a rolling deployment in which a migration
     * adding a tenth term has run and half the instances are still the previous build.
     * Those instances score without it, which is what they did yesterday.
     */
    public List<RankingWeight> findAll() {
        return jdbc.query(
                "SELECT term, weight, active, blocked_by, description, updated_at FROM ranking_weights",
                new MapSqlParameterSource(),
                RankingWeightRepository::toWeight)
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<RankingWeight> find(RankingTerm term) {
        MapSqlParameterSource params = new MapSqlParameterSource("term", term.wireValue());
        return jdbc
                .query(
                        "SELECT term, weight, active, blocked_by, description, updated_at"
                                + " FROM ranking_weights WHERE term = :term",
                        params,
                        RankingWeightRepository::toWeight)
                .stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Sets one term's weight and whether it is in the sum.
     *
     * <p>{@code blocked_by} and {@code description} are deliberately not settable. They
     * describe what a term <em>is</em> and what stands in its way, which are facts about
     * the code rather than about anybody's tuning: clearing {@code blocked_by} from an
     * admin screen would announce that a term is computed when nothing computes it,
     * which is the one thing V15's CHECK exists to prevent.
     *
     * @return the number of rows changed, which is one for a term that exists
     */
    public int update(RankingTerm term, BigDecimal weight, boolean active, UUID actorId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("term", term.wireValue())
                .addValue("weight", weight)
                .addValue("active", active)
                .addValue("actorId", actorId);
        return jdbc.update(
                "UPDATE ranking_weights SET weight = :weight, active = :active, updated_by = :actorId"
                        + " WHERE term = :term",
                params);
    }

    /**
     * One row of {@code ranking_weight_changes}, written in the same transaction as the
     * change it records.
     *
     * <p>Called only from {@code RankingService}, for the reason
     * {@code CollectionRepository.record} is: a weight change that succeeded while its
     * audit row was rolled back would be a change to what every backer sees with nobody's
     * name on it.
     */
    public void record(
            RankingTerm term,
            RankingWeight before,
            BigDecimal newWeight,
            boolean newActive,
            UUID actorId,
            String actorRole,
            String note) {

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("term", term.wireValue())
                .addValue("oldWeight", before == null ? null : before.weight())
                .addValue("newWeight", newWeight)
                .addValue("oldActive", before == null ? null : before.active())
                .addValue("newActive", newActive)
                .addValue("actorId", actorId)
                .addValue("actorRole", actorRole)
                .addValue("note", note);
        jdbc.update(
                """
                INSERT INTO ranking_weight_changes (
                    id, term, old_weight, new_weight, old_active, new_active, actor_id, actor_role, note)
                VALUES (:id, :term, :oldWeight, :newWeight, :oldActive, :newActive, :actorId, :actorRole, :note)
                """,
                params);
    }

    /**
     * A row, or empty when this build does not know the term. See {@link #findAll}.
     *
     * <p>{@code getBigDecimal} rather than {@code getDouble}: the column is
     * {@code numeric} and the value multiplies an expression whose exactness the keyset
     * cursor depends on.
     */
    private static Optional<RankingWeight> toWeight(ResultSet resultSet, int index) throws SQLException {
        Optional<RankingTerm> term = RankingTerm.fromWireValue(resultSet.getString("term"));
        if (term.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime updatedAt = resultSet.getObject("updated_at", OffsetDateTime.class);
        return Optional.of(new RankingWeight(
                term.get(),
                resultSet.getBigDecimal("weight"),
                resultSet.getBoolean("active"),
                resultSet.getString("blocked_by"),
                resultSet.getString("description"),
                updatedAt == null ? null : updatedAt.toInstant()));
    }
}
