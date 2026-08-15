package az.ideanest.support;

import az.ideanest.project.infrastructure.CategoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The one patch that takes a fresh draft to a campaign §5.3 will accept for
     * submission.
     *
     * <p>Here rather than copied into each suite because three test classes create
     * campaigns in order to submit them, and a fixture that is complete in two of
     * them and out of date in the third produces a failure about the fixture wearing
     * the name of the behaviour being tested. When §5.3 gains a requirement, this is
     * the one place the suites have to be taught about it.
     *
     * <p><strong>Deliberately only what blocks.</strong> No subcategory, no scheduled
     * launch, no reward tiers, and a story with no pictures in it — so a campaign
     * built from this is submittable and is not finished, which is exactly the
     * distinction the completeness checklist exists to draw and what
     * {@code ProjectChecklistApiTests} uses to tell advice apart from refusal.
     *
     * <p>Sent as a single body because that is what the editor's autosave would
     * eventually produce field by field, and because a fixture made of six requests
     * is a fixture whose failures are six times harder to read.
     */
    public static Map<String, Object> completeBasics(CategoryRepository categories) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blurb", "A summary that fits inside a hundred and thirty-five characters.");
        body.put("categoryId", categories.findBySlug("games").orElseThrow().getId().toString());
        body.put("goal", Map.of("amount", "5000.00", "currency", "AZN"));
        body.put("durationDays", DEFAULT_DURATION_DAYS);
        // §5.3: at least 1024×576. The recorded dimensions are what the checklist
        // measures, because nothing on the server has ever seen the file.
        body.put("coverImage", Map.of("url", "https://cdn.example.com/cover.jpg", "width", 1600, "height", 900));
        body.put("story", story(600));
        // §5.3 requires two hundred characters, emphatically. A campaign that says
        // nothing about what could go wrong is the one that produces the refunds.
        body.put("risks", "The main risk is manufacturing capacity. ".repeat(6));
        return body;
    }

    /** A valid story document holding one paragraph of the requested length. */
    public static Map<String, Object> story(int characters) {
        return Map.of(
                "version",
                1,
                "blocks",
                List.of(Map.of(
                        "type",
                        "paragraph",
                        "spans",
                        List.of(Map.of("text", "b".repeat(characters), "marks", List.of())))));
    }
}
