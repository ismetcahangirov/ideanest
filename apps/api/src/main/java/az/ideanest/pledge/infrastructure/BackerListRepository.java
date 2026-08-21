package az.ideanest.pledge.infrastructure;

import az.ideanest.pledge.application.BackerBreakdown;
import az.ideanest.pledge.application.BackerFilter;
import az.ideanest.pledge.application.BackerPage;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * §4.7's CD-10, CD-07 and CD-08 as SQL: the backer report, its total, and the two splits
 * the charts are drawn from.
 *
 * <h2>Why this is raw SQL and not JPA</h2>
 *
 * <p>{@code DailyRollupRepository}'s argument, applied to a different query. Two of the
 * three statements here are grouped aggregates, and the third is a join across three
 * tables with a filter assembled at run time; loading a campaign's pledges as managed
 * entities to group them in Java would turn one scan into as many object graphs as the
 * campaign has backers, and it gets worse exactly as the campaign gets more successful.
 * {@code NamedParameterJdbcTemplate} for the same reason {@code discovery.infrastructure}
 * uses it: §11 names jOOQ for the day the queries justify it, and three statements do not.
 *
 * <h2>The state predicate is written twice, on purpose</h2>
 *
 * <p>Every statement carries the report's five states <strong>as literals</strong> and
 * then narrows them with a bound parameter. The literals are what
 * {@code pledges_backer_report_idx} is partial on, and a partial index is only usable when
 * the planner can prove the query implies its predicate — which it cannot do for
 * {@code state IN (:states)}, because the parameter's value is not known when the plan is
 * made. Dropping the literals costs a sequential scan of every pledge the campaign ever
 * took, including its expired reservations, which are the majority.
 *
 * <p>The literals are <strong>assembled from {@link BackerFilter#REPORTED}</strong> rather
 * than typed out, so the SQL and the enum cannot drift; the values are Java enum constants,
 * so there is nothing here for a caller to inject.
 *
 * <h2>Ordering and the cursor</h2>
 *
 * <p>{@code (backed_at DESC, id DESC)}, where {@code backed_at} is
 * {@code COALESCE(confirmed_at, created_at)} — V31's header says why the fallback is there
 * rather than a sort on a nullable column. The cursor is the previous page's last pledge
 * identifier, and the comparison is a row comparison against that pledge's own key, looked
 * up in the same statement:
 *
 * <pre>(backed_at, id) &lt; (SELECT backed_at, id FROM pledges WHERE id = :cursor …)</pre>
 *
 * <p>That keeps the cursor a bare UUID, which is what {@code CommentPage} already hands
 * clients, instead of an encoded pair they would be tempted to construct. <strong>An
 * unknown cursor, or one from another campaign, yields an empty page</strong>: the
 * subquery is scoped to this campaign, returns no row, and the comparison is unknown for
 * every candidate. That is the same answer as "you have read past the end", and it is the
 * one answer that cannot leak whether the identifier is real.
 */
@Repository
public class BackerListRepository {

    /**
     * The five states the report covers, as SQL literals, in a fixed order.
     *
     * <p>See the class comment: this is what makes {@code pledges_backer_report_idx}
     * usable. Sorted so that two builds produce the same statement text and therefore the
     * same plan cache entry.
     */
    private static final String REPORTED_STATES = BackerFilter.REPORTED.stream()
            .map(Enum::name)
            .sorted()
            .map(state -> "'" + state + "'")
            .collect(Collectors.joining(", "));

    /** {@code COALESCE(confirmed_at, created_at)}, spelled once. */
    private static final String BACKED_AT = "COALESCE(p.confirmed_at, p.created_at)";

    /**
     * The report's columns.
     *
     * <p><strong>The tier is an identifier and not a title.</strong> A
     * {@code LEFT JOIN reward_tiers} would be one statement instead of two, and it would
     * read a table the reward module owns from inside this one — the coupling
     * {@code ModuleBoundaryTests} forbids one layer up, arrived at through SQL. The title
     * comes from {@code RewardTitles}, which that module publishes for this;
     * {@code BackerReportService} joins the two in memory, bounded by the number of tiers a
     * campaign has.
     *
     * <p>{@code users} <em>is</em> joined, and the difference is ownership rather than
     * taste: {@code pledges.backer_id} is this module's own foreign key, and three other
     * modules already join {@code users} the same way for the same reason — an account's
     * display name has no owning module to publish it.
     */
    private static final String SELECT_BACKERS =
            """
            SELECT p.id                AS pledge_id,
                   u.name              AS backer_name,
                   u.email             AS backer_email,
                   p.is_anonymous      AS anonymous,
                   p.reward_tier_id    AS reward_tier_id,
                   p.total_amount      AS amount,
                   p.currency          AS currency,
                   p.state             AS state,
                   p.shipping_country  AS country,
                   COALESCE(p.confirmed_at, p.created_at) AS backed_at
              FROM pledges p
              JOIN users u ON u.id = p.backer_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BackerListRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * One page of the report, newest backer first.
     *
     * @param cursor the previous page's last pledge, or null for the first page
     * @param limit how many rows to return. Bounded by the caller
     */
    public List<BackerPage.Backer> page(UUID projectId, BackerFilter filter, UUID cursor, int limit) {
        MapSqlParameterSource parameters = parametersFor(projectId, filter);
        parameters.addValue("limit", limit);

        StringBuilder sql = new StringBuilder(SELECT_BACKERS).append(where(filter));
        if (cursor != null) {
            parameters.addValue("cursor", cursor);
            sql.append("""
                       AND (%s, p.id) < (
                               SELECT COALESCE(c.confirmed_at, c.created_at), c.id
                                 FROM pledges c
                                WHERE c.id = :cursor AND c.project_id = :projectId)
                    """.formatted(BACKED_AT));
        }
        sql.append(" ORDER BY backed_at DESC, p.id DESC LIMIT :limit");

        return jdbc.query(sql.toString(), parameters, BACKER);
    }

    /**
     * How many backers the filter matches.
     *
     * <p>A second statement rather than a window function on the first, because the first
     * is bounded by {@code LIMIT} and a window would make it count the whole match set on
     * every page. Two cheap index reads beat one that pays for the total each time.
     */
    public long count(UUID projectId, BackerFilter filter) {
        // The join to `users` only when the search needs it. A count does not select a
        // name, so joining unconditionally would be one index lookup per matching row for
        // a column nothing reads.
        String join = filter.term() == null ? "" : " JOIN users u ON u.id = p.backer_id";
        Long matched = jdbc.queryForObject(
                "SELECT count(*) FROM pledges p" + join + where(filter), parametersFor(projectId, filter), Long.class);
        return matched == null ? 0 : matched;
    }

    /**
     * Every matching backer, for the export, up to {@code cap} rows.
     *
     * <p>{@code cap + 1} is fetched and the caller compares: an export that hit the ceiling
     * has to say so, and a query that returned exactly {@code cap} rows cannot tell whether
     * it was the last one. Nothing streams — §10.2 answers this route with a file, and a
     * bounded materialised list is the shape that lets the row count and the truncation
     * flag be known before the first byte is written.
     */
    public List<BackerPage.Backer> all(UUID projectId, BackerFilter filter, int cap) {
        MapSqlParameterSource parameters = parametersFor(projectId, filter);
        parameters.addValue("limit", cap + 1);

        return jdbc.query(
                SELECT_BACKERS + where(filter) + " ORDER BY backed_at DESC, p.id DESC LIMIT :limit",
                parameters,
                BACKER);
    }

    /**
     * The accounts a filter matches, and nothing else about them — #98's audience.
     *
     * <p><strong>Identifiers rather than {@code BackerPage.Backer} rows, which is not an
     * optimisation.</strong> {@link #all} selects a name, an address and an amount because a
     * creator is about to read them; this answers a question the notification module asks while
     * translating an event, and that module has no business receiving a list of backers' email
     * addresses in order to work out who to tell. The narrow projection is the boundary.
     *
     * <p>{@code DISTINCT} because a segment's states can span more than one pledge per person in
     * principle — {@code pledges_project_backer_active_key} makes that impossible today, and
     * {@code PledgeProjectAudiences} explains why that index is a decision about checkout rather
     * than a promise to this query. Ordered by the identifier so the answer is <em>stable</em>,
     * which is the property {@code SegmentAudience} promises: a truncated audience that returned
     * a different subset on every call would mean a redelivered event telling a different set of
     * people.
     */
    public List<UUID> backerIds(UUID projectId, BackerFilter filter, int limit) {
        MapSqlParameterSource parameters = parametersFor(projectId, filter);
        parameters.addValue("limit", limit);

        // The join to `users` only when the search term needs it, exactly as `count` does: an
        // audience selects no name, so joining unconditionally would be one index lookup per
        // matching row for a column nothing reads.
        String join = filter.term() == null ? "" : " JOIN users u ON u.id = p.backer_id";
        return jdbc.queryForList(
                "SELECT DISTINCT p.backer_id FROM pledges p" + join + where(filter)
                        + " ORDER BY p.backer_id LIMIT :limit",
                parameters,
                UUID.class);
    }

    /**
     * CD-07 and CD-08: the campaign's totals, its reward mix and its destinations.
     *
     * <p>Three statements, all over the report's five states and none of them narrowed by a
     * filter. The splits are a property of the campaign rather than of whatever the creator
     * last typed into the search box — a pie that changed when a filter did would be read
     * as the campaign changing.
     *
     * @throws az.ideanest.shared.money.CurrencyMismatchException never from here. The
     *     currency is taken from the totals row, which counts the distinct currencies it
     *     summed; the caller decides what to do when that count is not one
     */
    public Totals totals(UUID projectId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("projectId", projectId);
        return jdbc.queryForObject(
                """
                SELECT count(*)                      AS backer_count,
                       coalesce(sum(p.total_amount), 0) AS amount,
                       count(DISTINCT p.currency)    AS currencies,
                       min(p.currency)               AS currency,
                       max(p.currency)               AS other_currency
                  FROM pledges p
                 WHERE p.project_id = :projectId
                   AND p.state IN (%s)
                """
                        .formatted(REPORTED_STATES),
                parameters,
                (rs, row) -> new Totals(
                        rs.getLong("backer_count"),
                        rs.getBigDecimal("amount"),
                        rs.getInt("currencies"),
                        rs.getString("currency"),
                        rs.getString("other_currency")));
    }

    /**
     * CD-07: one row per tier that has a backer, most valuable first.
     *
     * <p>Identifiers and totals only — the titles are the reward module's to publish, and
     * {@code SELECT_BACKERS} says why this statement does not join for them. The order is
     * settled here rather than after the titles are attached, so that the sort is by what a
     * tier took and never by what it happens to be called.
     */
    public List<RewardTotal> rewardTotals(UUID projectId) {
        return jdbc.query(
                """
                SELECT p.reward_tier_id     AS reward_tier_id,
                       count(*)             AS backer_count,
                       sum(p.total_amount)  AS amount
                  FROM pledges p
                 WHERE p.project_id = :projectId
                   AND p.state IN (%s)
                   AND p.reward_tier_id IS NOT NULL
                 GROUP BY p.reward_tier_id
                 ORDER BY amount DESC, backer_count DESC, reward_tier_id ASC
                """
                        .formatted(REPORTED_STATES),
                new MapSqlParameterSource("projectId", projectId),
                (rs, row) -> new RewardTotal(
                        rs.getObject("reward_tier_id", UUID.class),
                        rs.getLong("backer_count"),
                        rs.getBigDecimal("amount")));
    }

    /**
     * CD-08: one row per destination, most valuable first, with the pledges that named none
     * gathered under a null country.
     *
     * <p><strong>The unnamed group is a group and not a gap.</strong> A digital reward has
     * no destination and §4.5's PL-02 support has none either; dropping them would leave a
     * chart whose parts do not add up to the total beside it, which is a discrepancy
     * somebody has to reconcile by hand. It sorts by what it took, like every other group —
     * ordering it to the end would be a second rule to explain, and the client already
     * knows which entry it is by the absent country.
     *
     * <p>{@code NULLS LAST} applies only to the final tiebreak, so two groups with
     * identical totals order the named one first and never shuffle between reads.
     */
    public List<BackerBreakdown.CountrySlice> countrySlices(UUID projectId, String currency) {
        return jdbc.query(
                """
                SELECT p.shipping_country  AS country,
                       count(*)            AS backer_count,
                       sum(p.total_amount) AS amount
                  FROM pledges p
                 WHERE p.project_id = :projectId
                   AND p.state IN (%s)
                 GROUP BY p.shipping_country
                 ORDER BY amount DESC, backer_count DESC, country ASC NULLS LAST
                """
                        .formatted(REPORTED_STATES),
                new MapSqlParameterSource("projectId", projectId),
                (rs, row) -> new BackerBreakdown.CountrySlice(
                        rs.getString("country"), rs.getLong("backer_count"), Money.of(rs.getBigDecimal("amount"), currency)));
    }

    /**
     * The report's WHERE clause for a given filter.
     *
     * <p>Assembled rather than one statement with {@code (:x IS NULL OR …)} conditions on
     * every axis, because those defeat the index: PostgreSQL plans the statement once for
     * all four axes and cannot use a filter's selectivity when the filter might not be
     * there. Four short statements planned separately beat one that is always the worst
     * case.
     *
     * <p>Nothing in the returned string comes from a caller. The only interpolation is
     * {@link #REPORTED_STATES}, which is built from an enum; every value the client sent is
     * a bound parameter.
     */
    private static String where(BackerFilter filter) {
        StringBuilder clause = new StringBuilder(" WHERE p.project_id = :projectId AND p.state IN (")
                .append(REPORTED_STATES)
                .append(") AND p.state IN (:states)");

        if (!filter.rewardTiers().isEmpty()) {
            clause.append(" AND p.reward_tier_id IN (:rewardTiers)");
        }
        if (!filter.countries().isEmpty()) {
            clause.append(" AND p.shipping_country IN (:countries)");
        }
        if (filter.term() != null) {
            // `email` is citext and already case-insensitive; `name` is not, so both sides
            // use ILIKE and the column type decides nothing.
            clause.append(" AND (u.name ILIKE :term OR u.email ILIKE :term)");
        }
        return clause.toString();
    }

    private static MapSqlParameterSource parametersFor(UUID projectId, BackerFilter filter) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("projectId", projectId);
        parameters.addValue(
                "states", filter.effectiveStates().stream().map(PledgeState::name).sorted().toList());
        if (!filter.rewardTiers().isEmpty()) {
            parameters.addValue("rewardTiers", List.copyOf(filter.rewardTiers()));
        }
        if (!filter.countries().isEmpty()) {
            parameters.addValue("countries", List.copyOf(filter.countries()));
        }
        if (filter.term() != null) {
            parameters.addValue("term", contains(filter.term()));
        }
        return parameters;
    }

    /**
     * A search term as a {@code LIKE} pattern that matches it anywhere.
     *
     * <p>The wildcards are escaped first. Somebody searching for {@code 100%} means the
     * three characters, and an unescaped {@code %} would match every backer on the campaign
     * — a search box that silently returns everything is worse than one that returns
     * nothing. Case is {@code ILIKE}'s business and not this method's.
     */
    private static String contains(String term) {
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static final RowMapper<BackerPage.Backer> BACKER = (ResultSet rs, int row) -> backerOf(rs);

    private static BackerPage.Backer backerOf(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        return new BackerPage.Backer(
                rs.getObject("pledge_id", UUID.class),
                rs.getString("backer_name"),
                rs.getString("backer_email"),
                rs.getBoolean("anonymous"),
                rs.getObject("reward_tier_id", UUID.class),
                // The title is attached by the service, from what the reward module
                // publishes. This statement does not know one.
                null,
                Money.of(rs.getBigDecimal("amount"), currency),
                PledgeState.valueOf(rs.getString("state")),
                rs.getString("country"),
                // OffsetDateTime rather than Instant, which the PostgreSQL driver does not
                // map for a timestamptz. The same conversion DailyRollupRepository makes.
                rs.getObject("backed_at", OffsetDateTime.class).toInstant());
    }

    /**
     * The campaign's reported totals.
     *
     * @param currencies how many distinct currencies were summed. §7.3 says one; the caller
     *     refuses rather than reporting the addition of two different kinds of thing
     * @param currency the lowest currency code summed, which is the campaign's when there
     *     is only one
     * @param otherCurrency the highest. Equal to {@link #currency()} in every campaign the
     *     specification permits, and carried so that a refusal can <em>name</em> the two
     *     currencies rather than say that there were two
     */
    public record Totals(
            long backerCount, BigDecimal amount, int currencies, String currency, String otherCurrency) {

        /** The empty campaign, which {@code count(*)} reports as a row of zeroes. */
        public boolean isEmpty() {
            return backerCount == 0;
        }
    }

    /**
     * One tier's totals, before the reward module has said what the tier is called.
     *
     * <p>A repository type rather than {@link BackerBreakdown.RewardSlice}, because a slice
     * carries a title and this statement is the half of the answer that has none. The
     * service assembles the other half.
     */
    public record RewardTotal(UUID rewardTierId, long backerCount, BigDecimal amount) {
    }
}
