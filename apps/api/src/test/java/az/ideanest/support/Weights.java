package az.ideanest.support;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts §11.2's weights into the state a test needs, by writing the rows.
 *
 * <p><strong>For the suites that are testing what the weights do.</strong> The honest
 * way to change one is {@code PUT /v1/admin/ranking/weights/{term}}, and
 * {@code RankingApiTests} takes it, because that path is what it is checking. A suite
 * about which campaign ranks first is not: driving every fixture through the admin API
 * would make each of its tests depend on the moderator configuration and on one account
 * sharing a sign-in rate limiter, for a configuration that is a precondition rather than
 * a subject.
 *
 * <p>Every check constraint in V15 still applies, so the rows that result are ones the
 * application could have produced — including
 * {@code ranking_weights_inert_terms_are_not_active}, which is why {@link #only} can
 * activate the four live terms and no others.
 *
 * <p>What is <em>not</em> written is the audit row. Weights changed this way have a
 * history that does not mention the change, so a test that reads
 * {@code ranking_weight_changes} must not use this.
 *
 * <p><strong>Writing the table is not enough on its own.</strong> {@code
 * RankingWeightStore} caches for a minute, which is the whole point of it — so a suite
 * that writes here must either refresh the store or move the clock past the window.
 * Both are exercised deliberately in {@code RankingRelevanceTests}.
 */
public final class Weights {

    /** V15's seed, so a suite can put the platform back the way it found it. */
    private static final Map<String, String> DEFAULTS = defaults();

    private Weights() {
    }

    /** Sets one term, without touching the others. */
    public static void set(DataSource dataSource, String term, String weight, boolean active) {
        int updated = new JdbcTemplate(dataSource)
                .update(
                        "UPDATE ranking_weights SET weight = ?, active = ? WHERE term = ?",
                        new BigDecimal(weight),
                        active,
                        term);
        if (updated != 1) {
            // A fixture that silently set nothing would leave every assertion after it
            // passing against the seeded weights.
            throw new IllegalStateException("No ranking weight named " + term);
        }
    }

    /**
     * Switches everything off except one term, which is weighted 1.
     *
     * <p><strong>The shape almost every ranking assertion needs.</strong> "This term
     * moves the order in the direction it claims" is only a statement about that term
     * when nothing else is contributing; with the seeded weights in force, a campaign
     * that wins on completion can be pushed below one that wins on recency, and the test
     * would be asserting the sum rather than the term.
     */
    public static void only(DataSource dataSource, String term) {
        new JdbcTemplate(dataSource).update("UPDATE ranking_weights SET weight = 0, active = false");
        set(dataSource, term, "1", true);
    }

    /** Every term off, so the composite is zero for every campaign. */
    public static void none(DataSource dataSource) {
        new JdbcTemplate(dataSource).update("UPDATE ranking_weights SET weight = 0, active = false");
    }

    /**
     * V15's seeded values, restored.
     *
     * <p>Called from an {@code @AfterEach} rather than only a {@code @BeforeEach},
     * because the Spring context and therefore the database are shared with every other
     * suite in the run — a class that left the editorial weight at 1 would silently
     * change what any later suite's relevance assertions mean.
     */
    public static void restoreDefaults(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM ranking_weight_changes");
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String[] parts = entry.getValue().split(":");
            // `updated_by` goes back to null with the rest of it, and not as tidiness:
            // V15 makes it a reference to `users`, so a moderator left named here cannot
            // be deleted — and the account that made the change is the suite's own
            // throwaway moderator. What that produced was a failure in whichever suite
            // happened to run next and clear `users`, which is the worst shape a test
            // leak can take: a suite failing for something another suite did.
            jdbc.update(
                    "UPDATE ranking_weights SET weight = ?, active = ?, updated_by = NULL WHERE term = ?",
                    new BigDecimal(parts[0]),
                    Boolean.parseBoolean(parts[1]),
                    entry.getKey());
        }
    }

    private static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("text_match", "0.35:true");
        values.put("pledge_velocity", "0:false");
        values.put("backer_velocity", "0:false");
        values.put("completion", "0.20:true");
        values.put("editorial", "0.15:true");
        values.put("conversion", "0:false");
        values.put("personalisation", "0:false");
        values.put("recency", "0.30:true");
        values.put("spam", "0:false");
        return Map.copyOf(values);
    }
}
