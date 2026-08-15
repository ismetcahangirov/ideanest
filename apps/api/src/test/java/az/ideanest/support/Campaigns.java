package az.ideanest.support;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a campaign into the state a test needs to start from, by writing the row.
 *
 * <p><strong>For suites that are not testing the lifecycle.</strong> The honest way
 * into {@code LIVE} is the four requests a creator actually makes — submit,
 * approve, launch, and a moderator to do the middle one — and
 * {@code ProjectLifecycleApiTests} takes it, because that path is what it is
 * checking. A suite about reward tiers is not: driving every one of its fixtures
 * through moderation would make each of its tests depend on the moderation
 * configuration and on a second account sharing one rate limiter, for a campaign
 * whose state is a precondition rather than a subject.
 *
 * <p>What is written is exactly what the launch transition writes — the state, the
 * launch instant, and the deadline computed from the duration — so the row that
 * results is one the application could have produced. Every check constraint on
 * {@code projects} still applies, including
 * {@code projects_public_states_are_fully_specified}, which is why the goal and the
 * duration are filled in here when the campaign has none.
 *
 * <p>What is <em>not</em> written is the audit row. A campaign launched this way has
 * a history that does not mention its launch, so a test that reads
 * {@code project_state_transitions} must not use this.
 */
public final class Campaigns {

    /** What a campaign gets when a test did not care. Inside §5.3's 1–60 days. */
    private static final int DEFAULT_DURATION_DAYS = 30;

    private Campaigns() {
    }

    /** Takes the campaign live, as of now, keeping any goal and duration it already has. */
    public static void launch(DataSource dataSource, UUID projectId) {
        int updated = new JdbcTemplate(dataSource)
                .update(
                        """
                        UPDATE projects
                           SET state = 'LIVE',
                               goal_amount = COALESCE(goal_amount, 5000.00),
                               duration_days = COALESCE(duration_days, ?),
                               launched_at = now(),
                               deadline = now() + make_interval(days => COALESCE(duration_days, ?))
                         WHERE id = ?
                        """,
                        DEFAULT_DURATION_DAYS,
                        DEFAULT_DURATION_DAYS,
                        projectId);

        if (updated != 1) {
            // A fixture that silently launched nothing would leave every assertion
            // after it passing against a draft.
            throw new IllegalStateException("No campaign " + projectId + " to launch");
        }
    }
}
